use chrono::NaiveDateTime;
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
    pub last_daily_reset: NaiveDateTime,
    pub last_monthly_reset: NaiveDateTime,
    pub created_at: NaiveDateTime,
    pub updated_at: NaiveDateTime,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {
    #[sea_orm(has_many = "super::sms_request::Entity")]
    SmsRequest,
}

impl Related<super::sms_request::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::SmsRequest.def()
    }
}

impl ActiveModelBehavior for ActiveModel {}
