use anyhow::{Context, Result};
use chrono::Utc;
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use reqwest::{
    header::{HeaderMap, HeaderValue, RETRY_AFTER},
    Client, StatusCode,
};
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tokio::sync::{Mutex, RwLock};
use tracing::{error, info, warn};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const TOKEN_REFRESH_MARGIN_SECS: i64 = 60;
const FCM_MAX_ATTEMPTS: u32 = 3;
const FCM_RETRY_BASE_DELAY: Duration = Duration::from_secs(10);
const FCM_QUOTA_RETRY_DELAY: Duration = Duration::from_secs(60);
const FCM_MAX_RETRY_DELAY: Duration = Duration::from_secs(120);

#[derive(Debug, Clone, Deserialize)]
pub struct ServiceAccountCredentials {
    pub project_id: String,
    pub private_key_id: String,
    pub private_key: String,
    pub client_email: String,
    pub token_uri: String,
}

#[derive(Serialize)]
struct JwtClaims {
    iss: String,
    scope: String,
    aud: String,
    iat: i64,
    exp: i64,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: i64,
}

struct CachedToken {
    access_token: String,
    expires_at: i64,
}

pub struct FirebaseClient {
    credentials: ServiceAccountCredentials,
    http_client: Client,
    cached_token: RwLock<Option<CachedToken>>,
    token_refresh: Mutex<()>,
}

impl FirebaseClient {
    pub fn new(credentials: ServiceAccountCredentials) -> Result<Self> {
        Ok(Self {
            credentials,
            http_client: Client::builder()
                .connect_timeout(CONNECT_TIMEOUT)
                .timeout(REQUEST_TIMEOUT)
                .build()
                .context("Failed to build Firebase HTTP client")?,
            cached_token: RwLock::new(None),
            token_refresh: Mutex::new(()),
        })
    }

    pub fn from_json_file(path: &str) -> Result<Self> {
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("Failed to read Firebase credentials file: {path}"))?;
        let credentials: ServiceAccountCredentials =
            serde_json::from_str(&content).context("Failed to parse Firebase credentials JSON")?;
        Self::new(credentials)
    }

    async fn valid_cached_token(&self) -> Option<String> {
        self.cached_token
            .read()
            .await
            .as_ref()
            .filter(|token| token.expires_at > Utc::now().timestamp() + TOKEN_REFRESH_MARGIN_SECS)
            .map(|token| token.access_token.clone())
    }

    async fn get_access_token(&self) -> Result<String> {
        if let Some(token) = self.valid_cached_token().await {
            return Ok(token);
        }

        // Serialize refreshes, not cache access, and recheck after waiting for a refresh.
        let _refresh = self.token_refresh.lock().await;
        if let Some(token) = self.valid_cached_token().await {
            return Ok(token);
        }

        let now = Utc::now().timestamp();
        let jwt = self.create_jwt()?;
        let params = [
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", jwt.as_str()),
        ];

        let response = self
            .http_client
            .post(&self.credentials.token_uri)
            .form(&params)
            .send()
            .await
            .context("Failed to send token request")?;
        let status = response.status();
        if !status.is_success() {
            // OAuth 오류 본문(invalid_grant 등)이 자격증명 문제 진단의 핵심이므로 함께 남긴다.
            let body = read_body(response).await;
            anyhow::bail!("Token endpoint returned {status}: {body}");
        }
        let response: TokenResponse = response
            .json()
            .await
            .context("Failed to parse token response")?;

        anyhow::ensure!(
            !response.access_token.is_empty() && response.expires_in > TOKEN_REFRESH_MARGIN_SECS,
            "Token endpoint returned an empty or short-lived token"
        );
        *self.cached_token.write().await = Some(CachedToken {
            access_token: response.access_token.clone(),
            expires_at: now + response.expires_in,
        });

        Ok(response.access_token)
    }

    fn create_jwt(&self) -> Result<String> {
        let now = Utc::now().timestamp();
        let claims = JwtClaims {
            iss: self.credentials.client_email.clone(),
            scope: "https://www.googleapis.com/auth/firebase.messaging".to_string(),
            aud: self.credentials.token_uri.clone(),
            iat: now,
            exp: now + 3600,
        };

        let key = EncodingKey::from_rsa_pem(self.credentials.private_key.as_bytes())
            .context("Failed to parse RSA private key")?;

        encode(&Header::new(Algorithm::RS256), &claims, &key).context("Failed to encode JWT")
    }

    pub async fn send_sms_request(
        &self,
        fcm_token: &str,
        request_id: &str,
        phone_number: &str,
        sender_phone_number: &str,
        message: &str,
    ) -> Result<()> {
        let data = serde_json::json!({
            "requestId": request_id,
            "phoneNumber": phone_number,
            "senderPhoneNumber": sender_phone_number,
            "message": message,
        });

        let body = serde_json::json!({
            "message": {
                "token": fcm_token,
                "data": data,
            }
        });

        let url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            self.credentials.project_id
        );

        self.send_fcm(&url, &body).await?;
        info!("FCM message sent successfully for request {request_id}");
        Ok(())
    }

    async fn send_fcm(&self, url: &str, body: &serde_json::Value) -> Result<()> {
        let mut attempt = 1;
        loop {
            // 백오프 대기가 토큰 만료 여유를 넘길 수 있어 시도마다 다시 얻는다(캐시 히트면 비용 없음).
            let access_token = self.get_access_token().await?;
            // 전송 타임아웃은 FCM이 이미 메시지를 받은 뒤일 수 있으므로, 모호한 전송은 재시도하지 않는다.
            let response = self
                .http_client
                .post(url)
                .bearer_auth(&access_token)
                .json(body)
                .send()
                .await
                .context("Failed to send FCM request")?;
            let status = response.status();
            if status.is_success() {
                return Ok(());
            }

            let delay = fcm_retry_delay(status, response.headers(), attempt);
            let response_body = read_body(response).await;
            let Some(delay) = delay else {
                error!("FCM request failed: status={status}, body={response_body}");
                anyhow::bail!("FCM request failed with status {status}: {response_body}");
            };
            warn!(
                "FCM returned {status}; retrying in {delay:?} (attempt {attempt}/{FCM_MAX_ATTEMPTS}): {response_body}"
            );
            tokio::time::sleep(delay).await;
            attempt += 1;
        }
    }
}

async fn read_body(response: reqwest::Response) -> String {
    response
        .text()
        .await
        .unwrap_or_else(|e| format!("<failed to read body: {e}>"))
}

/// 재시도까지 기다릴 시간. `None`이면 재시도하지 않고 즉시 실패시킨다.
///
/// 재시도 대상을 429/500/503으로 좁힌 것은 의도적이다. 이 코드들은 FCM이 메시지를
/// 받지 않았음을 명시적으로 알린 경우이므로 재전송이 안전하다. 반면 502/504나 전송
/// 타임아웃은 중간 경로에서 응답만 유실됐을 수 있어, 재시도하면 SMS가 두 번 발송될 수 있다.
fn fcm_retry_delay(status: StatusCode, headers: &HeaderMap, attempt: u32) -> Option<Duration> {
    if attempt >= FCM_MAX_ATTEMPTS || !matches!(status.as_u16(), 429 | 500 | 503) {
        return None;
    }

    let base = if status == StatusCode::TOO_MANY_REQUESTS {
        FCM_QUOTA_RETRY_DELAY
    } else {
        FCM_RETRY_BASE_DELAY
    };
    let mut delay = base * 2_u32.pow(attempt - 1);
    // 서버가 더 기다리라고 하면 따르되, 계산된 백오프보다 일찍 재시도하지는 않는다.
    // 헤더를 읽지 못하면 무시하고 백오프만 적용한다.
    if let Some(retry_after) = headers.get(RETRY_AFTER).and_then(parse_retry_after) {
        delay = delay.max(retry_after);
    }
    // 과도한 대기는 재시도 대신 즉시 실패로 처리한다(요청이 `Sending`에 오래 머무는 것 방지).
    (delay <= FCM_MAX_RETRY_DELAY).then_some(delay)
}

fn parse_retry_after(value: &HeaderValue) -> Option<Duration> {
    let value = value.to_str().ok()?.trim();
    if let Ok(seconds) = value.parse::<u64>() {
        return Some(Duration::from_secs(seconds));
    }
    // HTTP 날짜(IMF-fixdate)의 `GMT` 표기는 RFC2822 파서가 그대로 받는다.
    let deadline = chrono::DateTime::parse_from_rfc2822(value).ok()?;
    // 이미 지난 시각이면 음수가 되어 `to_std()`가 실패하고, 백오프만 적용된다.
    (deadline.with_timezone(&Utc) - Utc::now()).to_std().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn headers(retry_after: Option<&str>) -> HeaderMap {
        let mut headers = HeaderMap::new();
        if let Some(value) = retry_after {
            headers.insert(RETRY_AFTER, HeaderValue::from_str(value).unwrap());
        }
        headers
    }

    fn secs(seconds: u64) -> Option<Duration> {
        Some(Duration::from_secs(seconds))
    }

    #[test]
    fn retryable_statuses_back_off_exponentially() {
        let h = headers(None);
        assert_eq!(
            fcm_retry_delay(StatusCode::INTERNAL_SERVER_ERROR, &h, 1),
            secs(10)
        );
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &h, 2),
            secs(20)
        );
        assert_eq!(
            fcm_retry_delay(StatusCode::TOO_MANY_REQUESTS, &h, 1),
            secs(60)
        );
        assert_eq!(
            fcm_retry_delay(StatusCode::TOO_MANY_REQUESTS, &h, 2),
            secs(120)
        );
    }

    #[test]
    fn ambiguous_and_client_errors_are_not_retried() {
        let h = headers(None);
        for status in [
            StatusCode::BAD_REQUEST,
            StatusCode::UNAUTHORIZED,
            StatusCode::FORBIDDEN,
            StatusCode::NOT_FOUND,
            StatusCode::BAD_GATEWAY,
            StatusCode::GATEWAY_TIMEOUT,
        ] {
            assert_eq!(
                fcm_retry_delay(status, &h, 1),
                None,
                "{status} must not retry"
            );
        }
    }

    #[test]
    fn last_attempt_never_retries() {
        let h = headers(None);
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &h, FCM_MAX_ATTEMPTS),
            None
        );
    }

    #[test]
    fn retry_after_seconds_extends_but_never_shortens_backoff() {
        // 백오프(10초)보다 긴 값은 채택한다.
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some("30")), 1),
            secs(30)
        );
        // 더 짧은 값으로 일찍 재시도하지는 않는다.
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some("1")), 1),
            secs(10)
        );
    }

    #[test]
    fn excessive_retry_after_fails_fast_instead_of_waiting() {
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some("3600")), 1),
            None
        );
    }

    #[test]
    fn unparsable_retry_after_falls_back_to_backoff() {
        for value in ["not-a-date", "", "-5", "1.5"] {
            assert_eq!(
                fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some(value)), 1),
                secs(10),
                "{value:?} must fall back to the computed backoff"
            );
        }
    }

    #[test]
    fn retry_after_accepts_http_date_and_ignores_past_dates() {
        // IMF-fixdate(`GMT` 표기)를 해석해 백오프를 연장한다.
        // `to_rfc2822`가 초 미만을 버리므로 정확히 90초는 되지 않는다.
        let future = (Utc::now() + chrono::Duration::seconds(90)).to_rfc2822();
        let delay = fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some(&future)), 1)
            .expect("future Retry-After must extend the backoff");
        assert!(
            (Duration::from_secs(88)..=Duration::from_secs(90)).contains(&delay),
            "expected ~90s, got {delay:?}"
        );
        // 이미 지난 시각은 백오프를 줄이지 않는다.
        let past = "Sun, 06 Nov 1994 08:49:37 GMT";
        assert_eq!(
            fcm_retry_delay(StatusCode::SERVICE_UNAVAILABLE, &headers(Some(past)), 1),
            secs(10)
        );
    }
}
