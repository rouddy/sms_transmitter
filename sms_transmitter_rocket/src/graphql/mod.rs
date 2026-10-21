use async_graphql::{EmptySubscription, MergedObject, Schema};

pub mod fcm_token;
pub mod phone_number;
pub mod sms_request;

use fcm_token::{FcmTokenMutation, FcmTokenQuery};
use phone_number::PhoneNumberQuery;
use sms_request::{SmsRequestMutation, SmsRequestQuery};

#[derive(MergedObject, Default)]
pub struct QueryRoot(FcmTokenQuery, SmsRequestQuery, PhoneNumberQuery);

#[derive(MergedObject, Default)]
pub struct MutationRoot(FcmTokenMutation, SmsRequestMutation);

pub type AppSchema = Schema<QueryRoot, MutationRoot, EmptySubscription>;

#[cfg(test)]
mod tests {
    use super::{AppSchema, MutationRoot, QueryRoot};
    use crate::entity::{phone_number, sms_request};
    use crate::migration::Migrator;
    use async_graphql::{EmptySubscription, Schema};
    use chrono::{Local, NaiveDate};
    use sea_orm::{
        ActiveModelTrait, ColumnTrait, ConnectOptions, Database, DatabaseConnection, EntityTrait,
        QueryFilter, Set,
    };
    use sea_orm_migration::MigratorTrait;
    use uuid::Uuid;

    const NUMBER: &str = "01012345678";

    async fn setup() -> (AppSchema, DatabaseConnection) {
        // A single connection keeps every query on the same in-memory database.
        let mut options = ConnectOptions::new("sqlite::memory:");
        options.max_connections(1);
        let db = Database::connect(options).await.unwrap();
        Migrator::up(&db, None).await.unwrap();
        let schema = Schema::build(
            QueryRoot::default(),
            MutationRoot::default(),
            EmptySubscription,
        )
        .data(db.clone())
        .finish();
        (schema, db)
    }

    fn today() -> NaiveDate {
        Local::now().date_naive()
    }

    fn sent_counts(daily: i32, monthly: i32) -> String {
        format!(
            r#"{{ countedOn: "{}", dailySentCount: {daily}, monthlySentCount: {monthly} }}"#,
            today()
        )
    }

    async fn execute(schema: &AppSchema, query: String) {
        let response = schema.execute(query).await;
        assert!(response.errors.is_empty(), "{:?}", response.errors);
    }

    async fn register(schema: &AppSchema, token: &str, counts: Option<String>) {
        let counts = counts.map_or(String::new(), |c| format!(", sentCounts: {c}"));
        execute(
            schema,
            format!(
                r#"mutation {{ registerFcmToken(input: {{ token: "{token}", deviceInfo: "test",
                    phoneNumbers: [{{ phoneNumber: "{NUMBER}", dailyLimit: 10, monthlyLimit: 100{counts} }}] }}) {{ id }} }}"#
            ),
        )
        .await;
    }

    async fn report(schema: &AppSchema, request_id: Uuid, success: bool, counts: Option<String>) {
        let counts = counts.map_or(String::new(), |c| format!(", sentCounts: {c}"));
        execute(
            schema,
            format!(
                r#"mutation {{ reportSmsResult(input: {{ requestId: "{request_id}", success: {success}{counts} }}) }}"#
            ),
        )
        .await;
    }

    async fn phone(db: &DatabaseConnection) -> phone_number::Model {
        phone_number::Entity::find()
            .filter(phone_number::Column::PhoneNumber.eq(NUMBER))
            .one(db)
            .await
            .unwrap()
            .unwrap()
    }

    async fn counts(db: &DatabaseConnection) -> (i32, i32) {
        let row = phone(db).await;
        (
            row.effective_daily_sent_count(today()),
            row.effective_monthly_sent_count(today()),
        )
    }

    async fn insert_request(db: &DatabaseConnection) -> Uuid {
        let row = phone(db).await;
        let now = Local::now().naive_local();
        let id = Uuid::new_v4();
        sms_request::ActiveModel {
            id: Set(id),
            phone_number: Set("01099998888".to_string()),
            message: Set("hello".to_string()),
            callback_url: Set(None),
            callback_response_code: Set(None),
            callback_response_body: Set(None),
            status: Set(sms_request::SmsStatus::Sending),
            assigned_token_id: Set(Some(row.fcm_token_id)),
            assigned_phone_number_id: Set(Some(row.id)),
            error: Set(None),
            created_at: Set(now),
            updated_at: Set(now),
        }
        .insert(db)
        .await
        .unwrap();
        id
    }

    async fn status(db: &DatabaseConnection, id: Uuid) -> sms_request::SmsStatus {
        sms_request::Entity::find_by_id(id)
            .one(db)
            .await
            .unwrap()
            .unwrap()
            .status
    }

    #[tokio::test]
    async fn register_syncs_counts_without_decreasing_them() {
        let (schema, db) = setup().await;
        register(&schema, "token-a", Some(sent_counts(3, 30))).await;
        assert_eq!(counts(&db).await, (3, 30));

        register(&schema, "token-a", Some(sent_counts(1, 10))).await;
        assert_eq!(counts(&db).await, (3, 30));

        register(&schema, "token-a", None).await;
        assert_eq!(counts(&db).await, (3, 30));
    }

    #[tokio::test]
    async fn register_keeps_reported_counts_when_number_moves_to_another_token() {
        let (schema, db) = setup().await;
        register(&schema, "token-a", Some(sent_counts(3, 30))).await;
        let old_token_id = phone(&db).await.fcm_token_id;

        register(&schema, "token-b", Some(sent_counts(5, 50))).await;
        let moved = phone(&db).await;
        assert_ne!(moved.fcm_token_id, old_token_id);
        assert_eq!(counts(&db).await, (5, 50));
    }

    #[tokio::test]
    async fn repeated_report_syncs_counts_but_keeps_first_status() {
        let (schema, db) = setup().await;
        register(&schema, "token-a", None).await;
        let request_id = insert_request(&db).await;

        report(&schema, request_id, true, Some(sent_counts(1, 7))).await;
        assert_eq!(status(&db, request_id).await, sms_request::SmsStatus::Sent);
        assert_eq!(counts(&db).await, (1, 7));

        report(&schema, request_id, false, Some(sent_counts(2, 8))).await;
        assert_eq!(status(&db, request_id).await, sms_request::SmsStatus::Sent);
        assert_eq!(counts(&db).await, (2, 8));
    }

    #[tokio::test]
    async fn legacy_report_increments_once_and_persists() {
        let (schema, db) = setup().await;
        register(&schema, "token-a", None).await;
        let request_id = insert_request(&db).await;

        report(&schema, request_id, true, None).await;
        report(&schema, request_id, true, None).await;
        assert_eq!(counts(&db).await, (1, 1));
    }

    #[tokio::test]
    async fn legacy_failure_report_does_not_increment() {
        let (schema, db) = setup().await;
        register(&schema, "token-a", None).await;
        let request_id = insert_request(&db).await;

        report(&schema, request_id, false, None).await;
        assert_eq!(
            status(&db, request_id).await,
            sms_request::SmsStatus::Failed
        );
        assert_eq!(counts(&db).await, (0, 0));
    }
}
