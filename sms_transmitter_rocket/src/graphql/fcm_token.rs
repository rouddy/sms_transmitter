use async_graphql::{ComplexObject, Context, InputObject, Object, Result, SimpleObject, ID};
use chrono::Local;
use sea_orm::{
    sea_query::OnConflict, ActiveModelTrait, ColumnTrait, DatabaseConnection, EntityTrait,
    IntoActiveModel, QueryFilter, QueryOrder, Set, TransactionTrait,
};
use std::collections::HashMap;
use uuid::Uuid;

use crate::entity::{fcm_token, phone_number};
use crate::graphql::phone_number::{PhoneNumber, SentCountsInput};

#[derive(SimpleObject, Clone)]
#[graphql(complex)]
pub struct FcmToken {
    pub id: ID,
    pub token: String,
    pub device_info: String,
    pub created_at: String,
    pub updated_at: String,
    pub last_access_time: String,
}

#[ComplexObject]
impl FcmToken {
    pub async fn phone_numbers(&self, ctx: &Context<'_>) -> Result<Vec<PhoneNumber>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let token_uuid = Uuid::parse_str(&self.id)?;
        let rows = phone_number::Entity::find()
            .filter(phone_number::Column::FcmTokenId.eq(token_uuid))
            .order_by_asc(phone_number::Column::PhoneNumber)
            .all(db)
            .await?;
        Ok(rows.into_iter().map(PhoneNumber::from).collect())
    }
}

impl From<fcm_token::Model> for FcmToken {
    fn from(model: fcm_token::Model) -> Self {
        Self {
            id: ID(model.id.to_string()),
            token: model.token,
            device_info: model.device_info,
            created_at: model.created_at.to_string(),
            updated_at: model.updated_at.to_string(),
            last_access_time: model.last_access_time.to_string(),
        }
    }
}

#[derive(InputObject, Clone)]
pub struct PhoneNumberInput {
    pub phone_number: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    /// Sent counts from the device's SMS history, which may include messages relayed for
    /// another server. Omitted by older app versions.
    pub sent_counts: Option<SentCountsInput>,
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
            last_access_time: Set(now),
        };

        fcm_token::Entity::insert(active)
            .on_conflict(
                OnConflict::column(fcm_token::Column::Token)
                    .update_columns([
                        fcm_token::Column::DeviceInfo,
                        fcm_token::Column::UpdatedAt,
                        fcm_token::Column::LastAccessTime,
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
                let mut row = existing.clone();
                if let Some(counts) = input_row.sent_counts {
                    row.merge_reported_sent_counts(counts.into(), today);
                }
                let mut active_row = row.into_sent_counts_active_model();
                active_row.daily_limit = Set(input_row.daily_limit);
                active_row.monthly_limit = Set(input_row.monthly_limit);
                active_row.update(&txn).await?;
            } else {
                let other = phone_number::Entity::find()
                    .filter(phone_number::Column::PhoneNumber.eq(input_row.phone_number.clone()))
                    .one(&txn)
                    .await?;
                // A number new to this token starts from the device's counts (0 if not reported).
                let mut row = phone_number::Model {
                    id: other
                        .as_ref()
                        .map_or_else(Uuid::new_v4, |other_row| other_row.id),
                    fcm_token_id: model.id,
                    phone_number: input_row.phone_number.clone(),
                    daily_limit: input_row.daily_limit,
                    monthly_limit: input_row.monthly_limit,
                    daily_sent_count: 0,
                    monthly_sent_count: 0,
                    last_daily_reset: today,
                    last_monthly_reset: today,
                };
                if let Some(counts) = input_row.sent_counts {
                    row.merge_reported_sent_counts(counts.into(), today);
                }
                let active_row = row.into_active_model().reset_all();
                if other.is_some() {
                    active_row.update(&txn).await?;
                } else {
                    active_row.insert(&txn).await?;
                }
            }
        }

        txn.commit().await?;

        Ok(FcmToken::from(model))
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
        Ok(tokens.into_iter().map(FcmToken::from).collect())
    }
}

fn normalize_phone_numbers(phone_numbers: Vec<PhoneNumberInput>) -> Vec<PhoneNumberInput> {
    let mut normalized = HashMap::<String, PhoneNumberInput>::new();
    for input in phone_numbers {
        let Some(phone_number) = phone_number::normalize_phone_number(&input.phone_number) else {
            continue;
        };
        normalized.insert(
            phone_number.clone(),
            PhoneNumberInput {
                phone_number,
                daily_limit: input.daily_limit.max(0),
                monthly_limit: input.monthly_limit.max(0),
                sent_counts: input.sent_counts,
            },
        );
    }

    let mut values: Vec<PhoneNumberInput> = normalized.into_values().collect();
    values.sort_by(|a, b| a.phone_number.cmp(&b.phone_number));
    values
}
