use async_graphql::{Context, InputObject, Object, Result, SimpleObject, ID};
use chrono::{Datelike, Local};
use sea_orm::{
    sea_query::OnConflict, ActiveModelTrait, ColumnTrait, DatabaseConnection, EntityTrait,
    QueryFilter, QueryOrder, Set, TransactionTrait,
};
use std::collections::HashMap;
use uuid::Uuid;

use crate::entity::{fcm_token, phone_number};

#[derive(SimpleObject, Clone)]
pub struct PhoneNumberQuota {
    pub id: ID,
    pub phone_number: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
    pub daily_remaining: Option<i32>,
    pub monthly_remaining: Option<i32>,
    pub last_daily_reset: String,
    pub last_monthly_reset: String,
}

impl From<phone_number::Model> for PhoneNumberQuota {
    fn from(model: phone_number::Model) -> Self {
        let today = Local::now().date_naive();
        let daily_sent_count = if model.last_daily_reset < today {
            0
        } else {
            model.daily_sent_count
        };
        let monthly_sent_count = if (
            model.last_monthly_reset.year(),
            model.last_monthly_reset.month(),
        ) < (today.year(), today.month())
        {
            0
        } else {
            model.monthly_sent_count
        };
        let daily_remaining = model.daily_remaining();
        let monthly_remaining = model.monthly_remaining();
        let id = ID(model.id.to_string());
        let phone_number = model.phone_number.clone();
        let daily_limit = model.daily_limit;
        let monthly_limit = model.monthly_limit;
        let last_daily_reset = model.last_daily_reset.to_string();
        let last_monthly_reset = model.last_monthly_reset.to_string();

        Self {
            id,
            phone_number,
            daily_limit,
            monthly_limit,
            daily_sent_count,
            monthly_sent_count,
            daily_remaining,
            monthly_remaining,
            last_daily_reset,
            last_monthly_reset,
        }
    }
}

#[derive(SimpleObject)]
pub struct FcmToken {
    pub id: ID,
    pub token: String,
    pub device_info: String,
    pub phone_numbers: Vec<PhoneNumberQuota>,
    pub created_at: String,
    pub updated_at: String,
}

impl FcmToken {
    fn from_model(model: fcm_token::Model, phone_numbers: Vec<phone_number::Model>) -> Self {
        Self {
            id: ID(model.id.to_string()),
            token: model.token,
            device_info: model.device_info,
            phone_numbers: phone_numbers
                .into_iter()
                .map(PhoneNumberQuota::from)
                .collect(),
            created_at: model.created_at.to_string(),
            updated_at: model.updated_at.to_string(),
        }
    }
}

#[derive(InputObject, Clone)]
pub struct PhoneNumberInput {
    pub phone_number: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
}

#[derive(InputObject)]
pub struct RegisterFcmTokenInput {
    pub token: String,
    pub device_info: String,
    pub phone_numbers: Vec<PhoneNumberInput>,
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
            created_at: Set(now),
            updated_at: Set(now),
        };

        fcm_token::Entity::insert(active)
            .on_conflict(
                OnConflict::column(fcm_token::Column::Token)
                    .update_columns([fcm_token::Column::DeviceInfo, fcm_token::Column::UpdatedAt])
                    .to_owned(),
            )
            .exec(&txn)
            .await?;

        let model = fcm_token::Entity::find()
            .filter(fcm_token::Column::Token.eq(token))
            .one(&txn)
            .await?
            .ok_or_else(|| async_graphql::Error::new("failed to load registered fcm token"))?;

        let existing_rows = phone_number::Entity::find()
            .filter(phone_number::Column::FcmTokenId.eq(model.id))
            .all(&txn)
            .await?;
        let existing_by_number: HashMap<String, phone_number::Model> = existing_rows
            .into_iter()
            .map(|row| (row.phone_number.clone(), row))
            .collect();

        let desired_numbers: Vec<String> = phone_numbers
            .iter()
            .map(|row| row.phone_number.clone())
            .collect();

        let mut delete_query = phone_number::Entity::delete_many()
            .filter(phone_number::Column::FcmTokenId.eq(model.id));
        if !desired_numbers.is_empty() {
            delete_query = delete_query
                .filter(phone_number::Column::PhoneNumber.is_not_in(desired_numbers.clone()));
        }
        delete_query.exec(&txn).await?;

        for input_row in &phone_numbers {
            if let Some(existing) = existing_by_number.get(&input_row.phone_number) {
                let mut active_row: phone_number::ActiveModel = existing.clone().into();
                active_row.daily_limit = Set(input_row.daily_limit);
                active_row.monthly_limit = Set(input_row.monthly_limit);
                active_row.update(&txn).await?;
            } else {
                phone_number::ActiveModel {
                    id: Set(Uuid::new_v4()),
                    fcm_token_id: Set(model.id),
                    phone_number: Set(input_row.phone_number.clone()),
                    daily_limit: Set(input_row.daily_limit),
                    monthly_limit: Set(input_row.monthly_limit),
                    daily_sent_count: Set(0),
                    monthly_sent_count: Set(0),
                    last_daily_reset: Set(today),
                    last_monthly_reset: Set(today),
                }
                .insert(&txn)
                .await?;
            }
        }

        let refreshed_phone_numbers = phone_number::Entity::find()
            .filter(phone_number::Column::FcmTokenId.eq(model.id))
            .order_by_asc(phone_number::Column::PhoneNumber)
            .all(&txn)
            .await?;

        txn.commit().await?;

        Ok(FcmToken::from_model(model, refreshed_phone_numbers))
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
        let token_ids: Vec<Uuid> = tokens.iter().map(|token| token.id).collect();
        let phone_numbers_by_token = load_phone_numbers_by_token_ids(db, &token_ids).await?;

        let mut result = Vec::with_capacity(tokens.len());
        for token in tokens {
            let phone_numbers = phone_numbers_by_token
                .get(&token.id)
                .cloned()
                .unwrap_or_default();
            result.push(FcmToken::from_model(token, phone_numbers));
        }

        Ok(result)
    }
}

async fn load_phone_numbers_by_token_ids(
    db: &DatabaseConnection,
    token_ids: &[Uuid],
) -> Result<HashMap<Uuid, Vec<phone_number::Model>>> {
    if token_ids.is_empty() {
        return Ok(HashMap::new());
    }

    let rows = phone_number::Entity::find()
        .filter(phone_number::Column::FcmTokenId.is_in(token_ids.iter().copied()))
        .order_by_asc(phone_number::Column::PhoneNumber)
        .all(db)
        .await?;

    let mut grouped = HashMap::<Uuid, Vec<phone_number::Model>>::new();
    for row in rows {
        grouped.entry(row.fcm_token_id).or_default().push(row);
    }
    Ok(grouped)
}

fn normalize_phone_numbers(phone_numbers: Vec<PhoneNumberInput>) -> Vec<PhoneNumberInput> {
    let mut normalized = HashMap::<String, PhoneNumberInput>::new();
    for input in phone_numbers {
        let phone_number = input.phone_number.trim().to_string();
        if phone_number.is_empty() {
            continue;
        }
        normalized.insert(
            phone_number.clone(),
            PhoneNumberInput {
                phone_number,
                daily_limit: input.daily_limit.max(0),
                monthly_limit: input.monthly_limit.max(0),
            },
        );
    }

    let mut values: Vec<PhoneNumberInput> = normalized.into_values().collect();
    values.sort_by(|a, b| a.phone_number.cmp(&b.phone_number));
    values
}
