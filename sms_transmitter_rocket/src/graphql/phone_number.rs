use async_graphql::{Context, InputObject, Object, Result, SimpleObject, ID};
use chrono::{Local, NaiveDate};
use sea_orm::{DatabaseConnection, EntityTrait};

use crate::entity::phone_number;
use crate::entity::phone_number::ReportedSentCounts;

#[derive(SimpleObject, Clone)]
pub struct PhoneNumber {
    pub id: ID,
    pub fcm_token_id: ID,
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

impl From<phone_number::Model> for PhoneNumber {
    fn from(model: phone_number::Model) -> Self {
        let today = Local::now().date_naive();
        let daily_sent_count = model.effective_daily_sent_count(today);
        let monthly_sent_count = model.effective_monthly_sent_count(today);
        let daily_remaining = model.daily_remaining(today);
        let monthly_remaining = model.monthly_remaining(today);
        Self {
            id: ID(model.id.to_string()),
            fcm_token_id: ID(model.fcm_token_id.to_string()),
            phone_number: model.phone_number,
            daily_limit: model.daily_limit,
            monthly_limit: model.monthly_limit,
            daily_sent_count,
            monthly_sent_count,
            daily_remaining,
            monthly_remaining,
            last_daily_reset: model.last_daily_reset.to_string(),
            last_monthly_reset: model.last_monthly_reset.to_string(),
        }
    }
}

#[derive(Default)]
pub struct PhoneNumberQuery;

#[Object]
impl PhoneNumberQuery {
    pub async fn phone_numbers(&self, ctx: &Context<'_>) -> Result<Vec<PhoneNumber>> {
        let db = ctx.data::<DatabaseConnection>()?;
        let rows = phone_number::Entity::find().all(db).await?;
        Ok(rows.into_iter().map(PhoneNumber::from).collect())
    }
}

/// Sent counts tallied by the device from its SMS history. The server merges them into its
/// own counts instead of incrementing per report, so duplicate reports cannot double-count.
#[derive(InputObject, Clone, Copy)]
pub struct SentCountsInput {
    /// Device-local date the counts were tallied on; `monthlySentCount` covers its month.
    pub counted_on: NaiveDate,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
}

impl From<SentCountsInput> for ReportedSentCounts {
    fn from(input: SentCountsInput) -> Self {
        Self {
            counted_on: input.counted_on,
            daily: input.daily_sent_count.max(0),
            monthly: input.monthly_sent_count.max(0),
        }
    }
}
