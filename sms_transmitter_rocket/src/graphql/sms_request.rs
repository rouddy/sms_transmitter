use async_graphql::{Context, Enum, InputObject, Object, Result, SimpleObject, ID};
use chrono::{Datelike, Utc};
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
        let now = Utc::now().naive_utc();
        let today = now.date();
        let this_month = (now.year(), now.month());

        let txn = db.begin().await?;

        let tokens = fcm_token::Entity::find().all(&txn).await?;

        let mut selected: Option<fcm_token::Model> = None;
        for token in tokens {
            let mut active: fcm_token::ActiveModel = token.clone().into();
            let mut modified = false;

            if token.last_daily_reset.date() < today {
                active.daily_sent_count = Set(0);
                active.last_daily_reset = Set(now);
                modified = true;
            }

            let reset_month = (
                token.last_monthly_reset.year(),
                token.last_monthly_reset.month(),
            );
            if reset_month < this_month {
                active.monthly_sent_count = Set(0);
                active.last_monthly_reset = Set(now);
                modified = true;
            }

            let daily_sent = match &active.daily_sent_count {
                sea_orm::ActiveValue::Set(v) => *v,
                _ => token.daily_sent_count,
            };

            let monthly_sent = match &active.monthly_sent_count {
                sea_orm::ActiveValue::Set(v) => *v,
                _ => token.monthly_sent_count,
            };

            if daily_sent < token.daily_limit && monthly_sent < token.monthly_limit {
                active.daily_sent_count = Set(daily_sent + 1);
                active.monthly_sent_count = Set(monthly_sent + 1);
                active.updated_at = Set(now);
                let updated = active.update(&txn).await?;
                selected = Some(updated);
                break;
            } else if modified {
                active.updated_at = Set(now);
                active.update(&txn).await?;
            }
        }

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
                        tracing::error!(
                            "Failed to send FCM message for request {request_id}: {e}"
                        );
                        if let Ok(Some(req)) =
                            sms_request::Entity::find_by_id(request_id)
                                .one(&db_clone)
                                .await
                        {
                            let mut active: sms_request::ActiveModel = req.into();
                            active.status = Set(STATUS_FAILED.to_string());
                            active.error = Set(Some(e.to_string()));
                            active.updated_at = Set(Utc::now().naive_utc());
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
        let now = Utc::now().naive_utc();

        let request_id = Uuid::parse_str(&input.request_id)
            .map_err(|_| async_graphql::Error::new("Invalid request ID"))?;

        let request = sms_request::Entity::find_by_id(request_id)
            .one(db)
            .await?
            .ok_or_else(|| async_graphql::Error::new("SMS request not found"))?;

        let mut active: sms_request::ActiveModel = request.into();
        if input.success {
            active.status = Set(STATUS_SENT.to_string());
            active.error = Set(None);
        } else {
            active.status = Set(STATUS_FAILED.to_string());
            active.error = Set(input.error);
        }
        active.updated_at = Set(now);
        active.update(db).await?;

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
