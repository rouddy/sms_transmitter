use async_graphql::{Context, Enum, InputObject, Object, Result, SimpleObject, ID};
use chrono::Local;
use reqwest::Client;
use sea_orm::{
    ActiveModelTrait, ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter, Set,
    TransactionTrait,
};
use std::net::{IpAddr, SocketAddr};
use std::sync::OnceLock;
use std::time::Duration;
use uuid::Uuid;

use crate::entity::{fcm_token, phone_number, sms_request};
use crate::firebase::FirebaseClient;

const ACTIVE_TOKEN_SECS: i64 = 7_776_000; // 90 days

const CALLBACK_RESPONSE_BODY_MAX_BYTES: usize = 16 * 1024;

#[derive(Enum, Copy, Clone, Eq, PartialEq)]
pub enum SmsStatus {
    Sending,
    Sent,
    Failed,
}

impl From<sms_request::SmsStatus> for SmsStatus {
    fn from(status: sms_request::SmsStatus) -> Self {
        match status {
            sms_request::SmsStatus::Sending => Self::Sending,
            sms_request::SmsStatus::Sent => Self::Sent,
            sms_request::SmsStatus::Failed => Self::Failed,
        }
    }
}

#[derive(SimpleObject)]
pub struct SmsRequest {
    pub id: ID,
    pub phone_number: String,
    pub message: String,
    pub callback_url: Option<String>,
    pub callback_response_code: Option<i32>,
    pub callback_response_body: Option<String>,
    pub status: SmsStatus,
    pub assigned_device_id: Option<ID>,
    pub assigned_phone_number_id: Option<ID>,
    pub error: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<sms_request::Model> for SmsRequest {
    fn from(m: sms_request::Model) -> Self {
        Self {
            status: m.status.into(),
            assigned_device_id: m.assigned_token_id.map(|id| ID(id.to_string())),
            assigned_phone_number_id: m.assigned_phone_number_id.map(|id| ID(id.to_string())),
            id: ID(m.id.to_string()),
            phone_number: m.phone_number,
            message: m.message,
            callback_url: m.callback_url,
            callback_response_code: m.callback_response_code,
            callback_response_body: m.callback_response_body,
            error: m.error,
            created_at: m.created_at.to_string(),
            updated_at: m.updated_at.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::SmsStatus;
    use crate::entity::sms_request;
    use async_graphql::{InputType, Value};

    #[test]
    fn status_conversion_preserves_graphql_values() {
        for (status, expected) in [
            (sms_request::SmsStatus::Sending, "SENDING"),
            (sms_request::SmsStatus::Sent, "SENT"),
            (sms_request::SmsStatus::Failed, "FAILED"),
        ] {
            let status = SmsStatus::from(status);
            assert_eq!(
                status.to_value(),
                Value::Enum(async_graphql::Name::new(expected))
            );
        }
    }
}

#[derive(InputObject)]
pub struct RequestSmsInput {
    pub phone_number: String,
    pub message: String,
    pub callback_url: Option<String>,
}

#[derive(InputObject)]
pub struct ReportSmsResultInput {
    pub request_id: ID,
    pub success: bool,
    pub error: Option<String>,
}

#[derive(Default)]
pub struct SmsRequestMutation;

#[Object]
impl SmsRequestMutation {
    pub async fn request_sms(
        &self,
        ctx: &Context<'_>,
        input: RequestSmsInput,
    ) -> Result<SmsRequest> {
        let db = ctx.data::<DatabaseConnection>()?;
        let firebase = ctx.data::<std::sync::Arc<FirebaseClient>>()?;
        let now = Local::now().naive_local();
        let today = now.date();

        let txn = db.begin().await?;

        let tokens = fcm_token::Entity::find()
            .filter(
                fcm_token::Column::LastAccessTime
                    .gt(now - chrono::Duration::seconds(ACTIVE_TOKEN_SECS)),
            )
            .all(&txn)
            .await?;
        let token_by_id = tokens
            .iter()
            .map(|token| (token.id, token.clone()))
            .collect::<std::collections::HashMap<_, _>>();
        let token_ids = token_by_id.keys().copied().collect::<Vec<_>>();
        let phone_numbers = phone_number::Entity::find()
            .filter(phone_number::Column::FcmTokenId.is_in(token_ids))
            .all(&txn)
            .await?;

        let selected = phone_numbers
            .iter()
            .filter(|phone_number| phone_number.can_send_now(today))
            .filter_map(|phone_number| {
                token_by_id
                    .get(&phone_number.fcm_token_id)
                    .cloned()
                    .map(|token| (phone_number.clone(), token))
            })
            .max_by(|(a, _), (b, _)| {
                a.availability_score(today)
                    .partial_cmp(&b.availability_score(today))
                    .unwrap_or(std::cmp::Ordering::Equal)
            });

        if selected.is_none() {
            return Err(async_graphql::Error::new("남은 sms 쿼터가 없습니다."));
        }

        let request_id = Uuid::new_v4();

        let (selected_phone_number, token) = selected.unwrap();
        let assigned_token_id = Some(token.id);
        let assigned_phone_number_id = Some(selected_phone_number.id);

        let sms_active = sms_request::ActiveModel {
            id: Set(request_id),
            phone_number: Set(input.phone_number.clone()),
            message: Set(input.message.clone()),
            callback_url: Set(input.callback_url.clone()),
            callback_response_code: Set(None),
            callback_response_body: Set(None),
            status: Set(sms_request::SmsStatus::Sending),
            assigned_token_id: Set(assigned_token_id),
            assigned_phone_number_id: Set(assigned_phone_number_id),
            error: Set(None),
            created_at: Set(now),
            updated_at: Set(now),
        };

        let sms_model = sms_active.insert(&txn).await?;
        txn.commit().await?;

        let firebase = firebase.clone();
        let phone_number = input.phone_number.clone();
        let message = input.message.clone();
        let sender_phone_number = selected_phone_number.phone_number.clone();
        let fcm_token_str = token.token.clone();
        let db_clone = db.clone();

        tokio::spawn(async move {
            match firebase
                .send_sms_request(
                    &fcm_token_str,
                    &request_id.to_string(),
                    &phone_number,
                    &sender_phone_number,
                    &message,
                )
                .await
            {
                Ok(_) => {
                    tracing::info!("FCM message dispatched for request {request_id}");
                }
                Err(e) => {
                    tracing::error!("Failed to send FCM message for request {request_id}: {e}");
                    if let Ok(Some(req)) = sms_request::Entity::find_by_id(request_id)
                        .one(&db_clone)
                        .await
                    {
                        let mut active: sms_request::ActiveModel = req.into();
                        active.status = Set(sms_request::SmsStatus::Failed);
                        active.error = Set(Some(e.to_string()));
                        active.updated_at = Set(Local::now().naive_local());
                        let _ = active.update(&db_clone).await;
                    }
                }
            }
        });

        Ok(SmsRequest::from(sms_model))
    }

    pub async fn report_sms_result(
        &self,
        ctx: &Context<'_>,
        input: ReportSmsResultInput,
    ) -> Result<bool> {
        let db = ctx.data::<DatabaseConnection>()?;
        let now = Local::now().naive_local();
        let txn = db.begin().await?;

        let request_id = Uuid::parse_str(&input.request_id)
            .map_err(|_| async_graphql::Error::new("Invalid request ID"))?;

        let request = sms_request::Entity::find_by_id(request_id)
            .one(&txn)
            .await?
            .ok_or_else(|| async_graphql::Error::new("SMS request not found"))?;

        if input.success {
            // `request_sms`는 항상 `assigned_phone_number_id`를 채운다. NULL인 행은
            // m20260813_000004(한도를 번호 단위로 이전) 이전에 생성돼 백필 시 NULL로
            // 남은 요청뿐이며, 증가시킬 번호를 특정할 수 없으므로 카운트를 생략한다.
            match request.assigned_phone_number_id {
                Some(phone_number_id) => {
                    let mut phone_number_model = phone_number::Entity::find_by_id(phone_number_id)
                        .one(&txn)
                        .await?
                        .ok_or_else(|| {
                            async_graphql::Error::new("assigned phone number not found")
                        })?;
                    phone_number_model.increment_sent_counts(now.date());
                    let phone_number_active: phone_number::ActiveModel = phone_number_model.into();
                    phone_number_active.update(&txn).await?;
                }
                None => tracing::warn!(
                    "SMS request {request_id} has no assigned phone number; skipping sent-count increment"
                ),
            }
        }

        if let Some(token_id) = request.assigned_token_id {
            if let Ok(Some(token)) = fcm_token::Entity::find_by_id(token_id).one(&txn).await {
                let mut token_active: fcm_token::ActiveModel = token.into();
                token_active.last_access_time = Set(now);
                if let Err(e) = token_active.update(&txn).await {
                    tracing::error!("Failed to update last_access_time for token {token_id}: {e}");
                }
            }
        }

        let callback_url = request.callback_url.clone();
        let mut active: sms_request::ActiveModel = request.into();
        if input.success {
            active.status = Set(sms_request::SmsStatus::Sent);
            active.error = Set(None);
        } else {
            active.status = Set(sms_request::SmsStatus::Failed);
            active.error = Set(input.error);
        }
        active.updated_at = Set(now);
        active.update(&txn).await?;

        txn.commit().await?;

        if let Some(callback_url) = callback_url {
            match invoke_callback_url(&callback_url).await {
                Ok(response) => {
                    save_callback_response(db, request_id, response.status_code, response.body)
                        .await;
                    if !response.is_success {
                        tracing::error!(
                            "Callback URL returned non-success status for request {:?}: {}",
                            input.request_id,
                            response.status_code
                        );
                    }
                }
                Err(e) => {
                    save_callback_response(db, request_id, -1, e.clone()).await;
                    tracing::error!(
                        "Failed to call callback URL for request {:?}: {}",
                        input.request_id,
                        e
                    );
                }
            }
        }

        Ok(true)
    }
}

async fn save_callback_response(
    db: &DatabaseConnection,
    request_id: Uuid,
    status_code: i32,
    body: String,
) {
    match sms_request::Entity::find_by_id(request_id).one(db).await {
        Ok(Some(req)) => {
            let mut callback_active: sms_request::ActiveModel = req.into();
            callback_active.callback_response_code = Set(Some(status_code));
            callback_active.callback_response_body = Set(Some(body));
            callback_active.updated_at = Set(Local::now().naive_local());
            if let Err(e) = callback_active.update(db).await {
                tracing::error!("Failed to save callback response for request {request_id}: {e}");
            }
        }
        Ok(None) => {
            tracing::error!("SMS request not found while saving callback response: {request_id}");
        }
        Err(e) => {
            tracing::error!("Failed to load SMS request while saving callback response for request {request_id}: {e}");
        }
    }
}

struct CallbackResponse {
    status_code: i32,
    body: String,
    is_success: bool,
}

async fn invoke_callback_url(callback_url: &str) -> std::result::Result<CallbackResponse, String> {
    let parsed_url =
        reqwest::Url::parse(callback_url).map_err(|e| format!("Invalid callback URL: {e}"))?;
    let callback_host = validate_callback_url(&parsed_url)?;
    let client = match callback_host {
        CallbackHost::Ip => callback_http_client().clone(),
        CallbackHost::Domain(domain) => {
            build_callback_client_with_resolved_host(&domain, &parsed_url).await?
        }
    };

    let response = client
        .get(parsed_url)
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .map_err(|e| format!("Callback request failed: {e}"))?;
    let status_code = response.status().as_u16() as i32;
    let is_success = response.status().is_success();
    let body_bytes = response
        .bytes()
        .await
        .map_err(|e| format!("Failed to read callback response body: {e}"))?;
    let body = String::from_utf8_lossy(
        &body_bytes[..body_bytes.len().min(CALLBACK_RESPONSE_BODY_MAX_BYTES)],
    )
    .to_string();

    Ok(CallbackResponse {
        status_code,
        body,
        is_success,
    })
}

fn callback_http_client() -> &'static Client {
    static CALLBACK_CLIENT: OnceLock<Client> = OnceLock::new();
    CALLBACK_CLIENT.get_or_init(Client::new)
}

enum CallbackHost {
    Ip,
    Domain(String),
}

fn validate_callback_url(url: &reqwest::Url) -> std::result::Result<CallbackHost, String> {
    if url.scheme() != "http" && url.scheme() != "https" {
        return Err("Callback URL must use http/https".to_string());
    }

    let host = url
        .host_str()
        .ok_or_else(|| "Callback URL must include a host".to_string())?;

    if host.eq_ignore_ascii_case("localhost") || host.to_ascii_lowercase().ends_with(".localhost") {
        return Err("Localhost callback URL is not allowed".to_string());
    }

    if let Ok(ip) = host.parse::<IpAddr>() {
        validate_callback_ip(ip)?;
        return Ok(CallbackHost::Ip);
    }

    Ok(CallbackHost::Domain(host.to_string()))
}

fn validate_callback_ip(ip: IpAddr) -> std::result::Result<(), String> {
    let blocked_ip = match ip {
        IpAddr::V4(v4) => {
            v4.is_private() || v4.is_loopback() || v4.is_link_local() || v4.is_unspecified()
        }
        IpAddr::V6(v6) => {
            v6.is_unique_local()
                || v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_unicast_link_local()
                || v6.is_multicast()
        }
    };
    if blocked_ip {
        return Err("Private or local callback IP is not allowed".to_string());
    }
    Ok(())
}

async fn build_callback_client_with_resolved_host(
    domain: &str,
    url: &reqwest::Url,
) -> std::result::Result<Client, String> {
    let port = url
        .port_or_known_default()
        .ok_or_else(|| "Callback URL must include a known port".to_string())?;
    let resolved_addrs: Vec<SocketAddr> = tokio::net::lookup_host((domain, port))
        .await
        .map_err(|e| format!("Failed to resolve callback host: {e}"))?
        .collect();

    if resolved_addrs.is_empty() {
        return Err("Callback host did not resolve to any address".to_string());
    }

    for addr in &resolved_addrs {
        validate_callback_ip(addr.ip())?;
    }

    reqwest::Client::builder()
        .resolve_to_addrs(domain, &resolved_addrs)
        .build()
        .map_err(|e| format!("Failed to build callback HTTP client: {e}"))
}

#[derive(Default)]
pub struct SmsRequestQuery;

#[Object]
impl SmsRequestQuery {
    pub async fn sms_requests(&self, ctx: &Context<'_>) -> Result<Vec<SmsRequest>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let requests = sms_request::Entity::find().all(db).await?;
        Ok(requests.into_iter().map(SmsRequest::from).collect())
    }

    pub async fn sms_request(&self, ctx: &Context<'_>, id: ID) -> Result<Option<SmsRequest>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let request_id =
            Uuid::parse_str(&id).map_err(|_| async_graphql::Error::new("Invalid ID"))?;

        let request = sms_request::Entity::find_by_id(request_id)
            .one(db)
            .await?
            .map(SmsRequest::from);

        Ok(request)
    }
}
