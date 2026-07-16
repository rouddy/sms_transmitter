use chrono::NaiveDateTime;
use sea_orm::entity::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, DeriveEntityModel, Serialize, Deserialize)]
#[sea_orm(table_name = "sms_requests")]
pub struct Model {
    #[sea_orm(primary_key, auto_increment = false)]
    pub id: Uuid,
    pub phone_number: String,
    pub message: String,
    pub callback_url: Option<String>,
    pub callback_response_code: Option<i32>,
    pub callback_response_body: Option<String>,
    pub status: String,
    pub assigned_token_id: Option<Uuid>,
    pub error: Option<String>,
    pub created_at: NaiveDateTime,
    pub updated_at: NaiveDateTime,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {
    #[sea_orm(
        belongs_to = "super::fcm_token::Entity",
        from = "Column::AssignedTokenId",
        to = "super::fcm_token::Column::Id"
    )]
    FcmToken,
}

impl Related<super::fcm_token::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::FcmToken.def()
    }
}

impl ActiveModelBehavior for ActiveModel {}
