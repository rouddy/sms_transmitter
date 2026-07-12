use async_graphql::{Context, Enum, InputObject, Object, Result, SimpleObject, ID};
use chrono::Local;
use sea_orm::{ActiveModelTrait, DatabaseConnection, EntityTrait, Set, TransactionTrait};
use uuid::Uuid;

use crate::entity::{fcm_token, sms_request};
use crate::firebase::FirebaseClient;

pub const STATUS_PENDING: &str = "Pending";
pub const STATUS_SENDING: &str = "Sending";
pub const STATUS_SENT: &str = "Sent";
pub const STATUS_FAILED: &str = "Failed";

#[derive(Enum, Copy, Clone, Eq, PartialEq)]
pub enum SmsStatus {
    Pending,
    Sending,
    Sent,
    Failed,
}

impl SmsStatus {
    fn from_str(s: &str) -> Self {
        match s {
            STATUS_SENDING => Self::Sending,
            STATUS_SENT => Self::Sent,
            STATUS_FAILED => Self::Failed,
            _ => Self::Pending,
        }
    }
}

#[derive(SimpleObject)]
pub struct SmsRequest {
    pub id: ID,
    pub phone_number: String,
    pub message: String,
    pub callback_url: Option<String>,
    pub status: SmsStatus,
    pub assigned_device_id: Option<ID>,
    pub error: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

impl From<sms_request::Model> for SmsRequest {
    fn from(m: sms_request::Model) -> Self {
        Self {
            status: SmsStatus::from_str(&m.status),
            assigned_device_id: m.assigned_token_id.map(|id| ID(id.to_string())),
            id: ID(m.id.to_string()),
            phone_number: m.phone_number,
            message: m.message,
            callback_url: m.callback_url,
            error: m.error,
            created_at: m.created_at.to_string(),
            updated_at: m.updated_at.to_string(),
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

        let txn = db.begin().await?;

        let tokens = fcm_token::Entity::find().all(&txn).await?;

        let selected = tokens
            .iter()
            .filter(|token| token.daily_remaining_ratio() > 0.0)
            .max_by(|a, b| {
                a.daily_remaining_ratio()
                    .partial_cmp(&b.daily_remaining_ratio())
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .cloned()
            .or_else(|| {
                tokens
                    .iter()
                    .max_by(|a, b| {
                        a.monthly_remaining_ratio()
                            .partial_cmp(&b.monthly_remaining_ratio())
                            .unwrap_or(std::cmp::Ordering::Equal)
                    })
                    .cloned()
            });

        let request_id = Uuid::new_v4();
        let (status, assigned_token_id) = if let Some(ref token) = selected {
            (STATUS_SENDING.to_string(), Some(token.id))
        } else {
            (STATUS_PENDING.to_string(), None)
        };

        let sms_active = sms_request::ActiveModel {
            id: Set(request_id),
            phone_number: Set(input.phone_number.clone()),
            message: Set(input.message.clone()),
            callback_url: Set(input.callback_url.clone()),
            status: Set(status),
            assigned_token_id: Set(assigned_token_id),
            error: Set(None),
            created_at: Set(now),
            updated_at: Set(now),
        };

        let sms_model = sms_active.insert(&txn).await?;
        txn.commit().await?;

        if let Some(token) = selected {
            let firebase = firebase.clone();
            let phone_number = input.phone_number.clone();
            let message = input.message.clone();
            let callback_url = input.callback_url.clone();
            let fcm_token_str = token.token.clone();
            let db_clone = db.clone();

            tokio::spawn(async move {
                match firebase
                    .send_sms_request(
                        &fcm_token_str,
                        &request_id.to_string(),
                        &phone_number,
                        &message,
                        callback_url.as_deref(),
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
                            active.status = Set(STATUS_FAILED.to_string());
                            active.error = Set(Some(e.to_string()));
                            active.updated_at = Set(Local::now().naive_local());
                            let _ = active.update(&db_clone).await;
                        }
                    }
                }
            });
        }

        Ok(SmsRequest::from(sms_model))
    }

    pub async fn report_sms_result(
        &self,
        ctx: &Context<'_>,
        input: ReportSmsResultInput,
    ) -> Result<bool> {
        let db = ctx.data::<DatabaseConnection>()?;
        let now = Local::now().naive_local();
        let reset_at = now.date();
        let txn = db.begin().await?;

        let request_id = Uuid::parse_str(&input.request_id)
            .map_err(|_| async_graphql::Error::new("Invalid request ID"))?;

        let request = sms_request::Entity::find_by_id(request_id)
            .one(&txn)
            .await?
            .ok_or_else(|| async_graphql::Error::new("SMS request not found"))?;

        if let Some(token_id) = request.assigned_token_id {
            let token = fcm_token::Entity::find_by_id(token_id)
                .one(&txn)
                .await?
                .ok_or_else(|| async_graphql::Error::new("FCM token not found"))?;

            let next_daily_sent_count = token.daily_sent_count + 1;
            let next_monthly_sent_count = token.monthly_sent_count + 1;
            let mut token_active: fcm_token::ActiveModel = token.into();
            token_active.daily_sent_count = Set(next_daily_sent_count);
            token_active.monthly_sent_count = Set(next_monthly_sent_count);
            token_active.last_daily_reset = Set(reset_at);
            token_active.last_monthly_reset = Set(reset_at);
            token_active.updated_at = Set(now);
            token_active.update(&txn).await?;
        }

        let mut active: sms_request::ActiveModel = request.into();
        if input.success {
            active.status = Set(STATUS_SENT.to_string());
            active.error = Set(None);
        } else {
            active.status = Set(STATUS_FAILED.to_string());
            active.error = Set(input.error);
        }
        active.updated_at = Set(now);
        active.update(&txn).await?;

        txn.commit().await?;

        Ok(true)
    }
}

#[derive(Default)]
pub struct SmsRequestQuery;

#[Object]
impl SmsRequestQuery {
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
