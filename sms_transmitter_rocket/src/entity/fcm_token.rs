use chrono::{Datelike, Local, NaiveDate, NaiveDateTime};
use sea_orm::entity::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, DeriveEntityModel, Serialize, Deserialize)]
#[sea_orm(table_name = "fcm_tokens")]
pub struct Model {
    #[sea_orm(primary_key, auto_increment = false)]
    pub id: Uuid,
    #[sea_orm(unique)]
    pub token: String,
    pub device_info: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
    pub last_daily_reset: NaiveDate,
    pub last_monthly_reset: NaiveDate,
    pub created_at: NaiveDateTime,
    pub updated_at: NaiveDateTime,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {
    #[sea_orm(has_many = "super::sms_request::Entity")]
    SmsRequest,
    #[sea_orm(has_many = "super::phone_number::Entity")]
    PhoneNumber,
}

impl Related<super::sms_request::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::SmsRequest.def()
    }
}

impl Related<super::phone_number::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::PhoneNumber.def()
    }
}

impl ActiveModelBehavior for ActiveModel {}

impl Model {
    pub fn daily_remaining_ratio(&self) -> f32 {
        if self.daily_limit == 0 {
            return 0.0;
        }
        let today = Local::now().date_naive();
        if self.last_daily_reset < today {
            return 1.0; // Reset daily count if it's a new day
        }
        let remaining = (self.daily_limit - self.daily_sent_count).max(0);
        remaining as f32 / self.daily_limit as f32
    }

    pub fn monthly_remaining_ratio(&self) -> f32 {
        if self.monthly_limit == 0 {
            return 0.0;
        }
        let today = Local::now().date_naive();
        if self.last_monthly_reset.month() < today.month()
            || self.last_monthly_reset.year() < today.year()
        {
            return 1.0; // Reset monthly count if it's a new month
        }
        let remaining = (self.monthly_limit - self.monthly_sent_count).max(0);
        remaining as f32 / self.monthly_limit as f32
    }
}
