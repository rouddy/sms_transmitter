use async_graphql::{Context, InputObject, Object, Result, SimpleObject, ID};
use chrono::Utc;
use sea_orm::{ActiveModelTrait, ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter, Set};
use uuid::Uuid;

use crate::entity::fcm_token;

#[derive(SimpleObject)]
pub struct FcmToken {
    pub id: ID,
    pub token: String,
    pub device_info: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
    pub daily_remaining: i32,
    pub monthly_remaining: i32,
    pub created_at: String,
    pub updated_at: String,
}

impl From<fcm_token::Model> for FcmToken {
    fn from(m: fcm_token::Model) -> Self {
        Self {
            daily_remaining: (m.daily_limit - m.daily_sent_count).max(0),
            monthly_remaining: (m.monthly_limit - m.monthly_sent_count).max(0),
            id: ID(m.id.to_string()),
            token: m.token,
            device_info: m.device_info,
            daily_limit: m.daily_limit,
            monthly_limit: m.monthly_limit,
            daily_sent_count: m.daily_sent_count,
            monthly_sent_count: m.monthly_sent_count,
            created_at: m.created_at.to_string(),
            updated_at: m.updated_at.to_string(),
        }
    }
}

#[derive(InputObject)]
pub struct RegisterFcmTokenInput {
    pub token: String,
    pub device_info: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
}

#[derive(Default)]
pub struct FcmTokenMutation;

#[Object]
impl FcmTokenMutation {
    pub async fn register_fcm_token(
        &self,
        ctx: &Context<'_>,
        input: RegisterFcmTokenInput,
    ) -> Result<FcmToken> {
        let db = ctx.data::<DatabaseConnection>()?;
        let now = Utc::now().naive_utc();

        let existing = fcm_token::Entity::find()
            .filter(fcm_token::Column::Token.eq(&input.token))
            .one(db)
            .await?;

        let model = if let Some(existing) = existing {
            let mut active: fcm_token::ActiveModel = existing.into();
            active.device_info = Set(input.device_info);
            active.daily_limit = Set(input.daily_limit);
            active.monthly_limit = Set(input.monthly_limit);
            active.updated_at = Set(now);
            active.update(db).await?
        } else {
            let active = fcm_token::ActiveModel {
                id: Set(Uuid::new_v4()),
                token: Set(input.token),
                device_info: Set(input.device_info),
                daily_limit: Set(input.daily_limit),
                monthly_limit: Set(input.monthly_limit),
                daily_sent_count: Set(0),
                monthly_sent_count: Set(0),
                last_daily_reset: Set(now),
                last_monthly_reset: Set(now),
                created_at: Set(now),
                updated_at: Set(now),
            };
            active.insert(db).await?
        };

        Ok(FcmToken::from(model))
    }
}

#[derive(Default)]
pub struct FcmTokenQuery;

#[Object]
impl FcmTokenQuery {
    pub async fn fcm_tokens(&self, ctx: &Context<'_>) -> Result<Vec<FcmToken>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let tokens = fcm_token::Entity::find().all(db).await?;
        Ok(tokens.into_iter().map(FcmToken::from).collect())
    }
}
