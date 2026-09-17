use chrono::NaiveDateTime;
use sea_orm::entity::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, EnumIter, DeriveActiveEnum, Serialize, Deserialize)]
#[sea_orm(rs_type = "String", db_type = "String(StringLen::None)")]
pub enum SmsStatus {
    #[sea_orm(string_value = "Sending")]
    Sending,
    #[sea_orm(string_value = "Sent")]
    Sent,
    #[sea_orm(string_value = "Failed")]
    Failed,
}

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
    pub status: SmsStatus,
    pub assigned_token_id: Option<Uuid>,
    pub assigned_phone_number_id: Option<Uuid>,
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
    #[sea_orm(
        belongs_to = "super::phone_number::Entity",
        from = "Column::AssignedPhoneNumberId",
        to = "super::phone_number::Column::Id"
    )]
    PhoneNumber,
}

impl Related<super::fcm_token::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::FcmToken.def()
    }
}

impl Related<super::phone_number::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::PhoneNumber.def()
    }
}

impl ActiveModelBehavior for ActiveModel {}

#[cfg(test)]
mod tests {
    use super::SmsStatus;
    use sea_orm::{ActiveEnum, ConnectionTrait, Database, DbBackend, Statement};

    #[test]
    fn status_preserves_existing_database_values() {
        for (status, value) in [
            (SmsStatus::Sending, "Sending"),
            (SmsStatus::Sent, "Sent"),
            (SmsStatus::Failed, "Failed"),
        ] {
            assert_eq!(status.to_value(), value);
            assert_eq!(SmsStatus::try_from_value(&value.to_string()).unwrap(), status);
        }
    }

    #[tokio::test]
    async fn sqlite_decodes_known_statuses_and_rejects_unknown_values() {
        let db = Database::connect("sqlite::memory:").await.unwrap();
        for (value, expected) in [
            ("Sending", Some(SmsStatus::Sending)),
            ("Sent", Some(SmsStatus::Sent)),
            ("Failed", Some(SmsStatus::Failed)),
            ("Unknown", None),
            ("sent", None),
            ("", None),
        ] {
            let row = db
                .query_one(Statement::from_sql_and_values(
                    DbBackend::Sqlite,
                    "SELECT ? AS status",
                    [value.into()],
                ))
                .await
                .unwrap()
                .unwrap();
            let status = row.try_get::<SmsStatus>("", "status");
            match expected {
                Some(expected) => assert_eq!(status.unwrap(), expected),
                None => assert!(status.is_err(), "unexpectedly accepted status: {value}"),
            }
        }
    }
}
