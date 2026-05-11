use anyhow::{Context, Result};
use chrono::Utc;
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::{error, info};

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
    cached_token: Arc<Mutex<Option<CachedToken>>>,
}

impl FirebaseClient {
    pub fn new(credentials: ServiceAccountCredentials) -> Self {
        Self {
            credentials,
            http_client: Client::new(),
            cached_token: Arc::new(Mutex::new(None)),
        }
    }

    pub fn from_json_file(path: &str) -> Result<Self> {
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("Failed to read Firebase credentials file: {path}"))?;
        let credentials: ServiceAccountCredentials = serde_json::from_str(&content)
            .context("Failed to parse Firebase credentials JSON")?;
        Ok(Self::new(credentials))
    }

    async fn get_access_token(&self) -> Result<String> {
        let mut cached = self.cached_token.lock().await;
        let now = Utc::now().timestamp();

        if let Some(ref token) = *cached {
            if token.expires_at > now + 60 {
                return Ok(token.access_token.clone());
            }
        }

        let jwt = self.create_jwt()?;
        let params = [
            ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
            ("assertion", jwt.as_str()),
        ];

        let response: TokenResponse = self
            .http_client
            .post(&self.credentials.token_uri)
            .form(&params)
            .send()
            .await
            .context("Failed to send token request")?
            .json()
            .await
            .context("Failed to parse token response")?;

        *cached = Some(CachedToken {
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
        message: &str,
        callback_url: Option<&str>,
    ) -> Result<()> {
        let access_token = self.get_access_token().await?;

        let mut data = serde_json::json!({
            "requestId": request_id,
            "phoneNumber": phone_number,
            "message": message,
        });

        if let Some(url) = callback_url {
            data["callback"] = serde_json::Value::String(url.to_string());
        }

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

        let response = self
            .http_client
            .post(&url)
            .bearer_auth(&access_token)
            .json(&body)
            .send()
            .await
            .context("Failed to send FCM request")?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "Failed to read body".to_string());
            error!("FCM request failed: status={status}, body={body}");
            anyhow::bail!("FCM request failed with status {status}: {body}");
        }

        info!("FCM message sent successfully to token: {fcm_token}");
        Ok(())
    }
}
