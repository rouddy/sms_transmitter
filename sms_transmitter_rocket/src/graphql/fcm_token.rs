use async_graphql::{Context, InputObject, Object, Result, SimpleObject, ID};
use chrono::Local;
use sea_orm::{
    sea_query::OnConflict, ColumnTrait, DatabaseConnection, EntityTrait, QueryFilter, QueryOrder, Set,
    TransactionTrait,
};
use std::collections::HashSet;
use uuid::Uuid;

use crate::entity::{fcm_token, phone_number};

#[derive(SimpleObject)]
pub struct FcmToken {
    pub id: ID,
    pub token: String,
    pub device_info: String,
    pub phone_numbers: Vec<String>,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
    pub daily_remaining: i32,
    pub monthly_remaining: i32,
    pub created_at: String,
    pub updated_at: String,
}

impl FcmToken {
    fn from_model(model: fcm_token::Model, phone_numbers: Vec<String>) -> Self {
        Self {
            daily_remaining: (model.daily_limit - model.daily_sent_count).max(0),
            monthly_remaining: (model.monthly_limit - model.monthly_sent_count).max(0),
            id: ID(model.id.to_string()),
            token: model.token,
            device_info: model.device_info,
            phone_numbers,
            daily_limit: model.daily_limit,
            monthly_limit: model.monthly_limit,
            daily_sent_count: model.daily_sent_count,
            monthly_sent_count: model.monthly_sent_count,
            created_at: model.created_at.to_string(),
            updated_at: model.updated_at.to_string(),
        }
    }
}

#[derive(InputObject)]
pub struct RegisterFcmTokenInput {
    pub token: String,
    pub device_info: String,
    pub phone_numbers: Vec<String>,
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
        let now = Local::now().naive_local();
        let today = now.date();
        let phone_numbers = normalize_phone_numbers(input.phone_numbers);

        let token = input.token.clone();
        let txn = db.begin().await?;

        let active = fcm_token::ActiveModel {
            id: Set(Uuid::new_v4()),
            token: Set(input.token),
            device_info: Set(input.device_info),
            daily_limit: Set(input.daily_limit),
            monthly_limit: Set(input.monthly_limit),
            daily_sent_count: Set(0),
            monthly_sent_count: Set(0),
            last_daily_reset: Set(today),
            last_monthly_reset: Set(today),
            created_at: Set(now),
            updated_at: Set(now),
        };

        fcm_token::Entity::insert(active)
            .on_conflict(
                OnConflict::column(fcm_token::Column::Token)
                    .update_columns([
                        fcm_token::Column::DeviceInfo,
                        fcm_token::Column::DailyLimit,
                        fcm_token::Column::MonthlyLimit,
                        fcm_token::Column::UpdatedAt,
                    ])
                    .to_owned(),
            )
            .exec(&txn)
            .await?;

        let model = fcm_token::Entity::find()
            .filter(fcm_token::Column::Token.eq(token))
            .one(&txn)
            .await?
            .ok_or_else(|| async_graphql::Error::new("failed to load registered fcm token"))?;

        phone_number::Entity::delete_many()
            .filter(phone_number::Column::FcmTokenId.eq(model.id))
            .exec(&txn)
            .await?;

        if !phone_numbers.is_empty() {
            phone_number::Entity::insert_many(phone_numbers.iter().map(|value| phone_number::ActiveModel {
                id: Set(Uuid::new_v4()),
                fcm_token_id: Set(model.id),
                phone_number: Set(value.clone()),
            }))
            .exec(&txn)
            .await?;
        }

        txn.commit().await?;

        Ok(FcmToken::from_model(model, phone_numbers))
    }

    pub async fn delete_fcm_token(&self, ctx: &Context<'_>, token_id: ID) -> Result<bool> {
        let db = ctx.data::<DatabaseConnection>()?;
        let token_uuid = Uuid::parse_str(&token_id.to_string())?;

        let result = fcm_token::Entity::delete_by_id(token_uuid).exec(db).await?;
        Ok(result.rows_affected > 0)
    }
}

#[derive(Default)]
pub struct FcmTokenQuery;

#[Object]
impl FcmTokenQuery {
    pub async fn fcm_tokens(&self, ctx: &Context<'_>) -> Result<Vec<FcmToken>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let tokens = fcm_token::Entity::find().all(db).await?;

        let mut result = Vec::with_capacity(tokens.len());
        for token in tokens {
            let phone_numbers = load_phone_numbers(db, token.id).await?;
            result.push(FcmToken::from_model(token, phone_numbers));
        }

        Ok(result)
    }
}

async fn load_phone_numbers(db: &DatabaseConnection, token_id: Uuid) -> Result<Vec<String>> {
    let rows = phone_number::Entity::find()
        .filter(phone_number::Column::FcmTokenId.eq(token_id))
        .order_by_asc(phone_number::Column::PhoneNumber)
        .all(db)
        .await?;

    Ok(rows.into_iter().map(|row| row.phone_number).collect())
}

fn normalize_phone_numbers(phone_numbers: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    phone_numbers
        .into_iter()
        .map(|phone_number| phone_number.trim().to_string())
        .filter(|phone_number| !phone_number.is_empty())
        .filter(|phone_number| seen.insert(phone_number.clone()))
        .collect()
}
