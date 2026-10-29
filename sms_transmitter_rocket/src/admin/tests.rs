use super::{has_quota, is_active, stage};
use crate::auth::{test_keys, JwtVerifier};
use crate::entity::{phone_number, sms_request};
use crate::graphql::{AppSchema, MutationRoot, QueryRoot};
use crate::migration::Migrator;
use async_graphql::{EmptySubscription, Schema};
use chrono::{Duration, Local};
use rocket::http::{ContentType, Header, Status};
use rocket::local::asynchronous::{Client, LocalResponse};
use sea_orm::{
    ActiveModelTrait, ColumnTrait, ConnectOptions, Database, DatabaseConnection, EntityTrait,
    QueryFilter, Set,
};
use sea_orm_migration::MigratorTrait;
use serde_json::json;
use uuid::Uuid;

async fn setup() -> (Client, AppSchema, DatabaseConnection) {
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
    let rocket = rocket::build()
        .manage(schema.clone())
        .manage(JwtVerifier::from_public_pem(test_keys::PUBLIC_KEY))
        .attach(stage());
    (Client::tracked(rocket).await.unwrap(), schema, db)
}

/// Logs in the way the login page does: a multipart form with the key as a file.
async fn login<'c>(client: &'c Client, pem: &str) -> LocalResponse<'c> {
    let boundary = "admin-test-boundary";
    let body = format!(
        "--{boundary}\r\n\
         Content-Disposition: form-data; name=\"key_file\"; filename=\"private_key.pem\"\r\n\
         Content-Type: application/octet-stream\r\n\r\n\
         {pem}\r\n\
         --{boundary}\r\n\
         Content-Disposition: form-data; name=\"key_text\"\r\n\r\n\
         \r\n\
         --{boundary}--\r\n"
    );
    client
        .post("/admin/login")
        .header(Header::new(
            "Content-Type",
            format!("multipart/form-data; boundary={boundary}"),
        ))
        .body(body)
        .dispatch()
        .await
}

async fn csrf_token(client: &Client) -> String {
    let body = body(client, "/admin").await;
    let marker = "name=\"csrf\" value=\"";
    let start = body.find(marker).expect("page has a CSRF field") + marker.len();
    body[start..].split('"').next().unwrap().to_string()
}

async fn body(client: &Client, uri: &str) -> String {
    let response = client.get(uri.to_string()).dispatch().await;
    assert_eq!(response.status(), Status::Ok, "GET {uri}");
    response.into_string().await.unwrap()
}

async fn post_form<'c>(
    client: &'c Client,
    uri: &str,
    fields: &[(&str, &str)],
) -> LocalResponse<'c> {
    let body = fields
        .iter()
        .map(|(name, value)| format!("{}={}", encode(name), encode(value)))
        .collect::<Vec<_>>()
        .join("&");
    client
        .post(uri.to_string())
        .header(ContentType::Form)
        .body(body)
        .dispatch()
        .await
}

fn encode(value: &str) -> String {
    value
        .bytes()
        .map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' => {
                (byte as char).to_string()
            }
            _ => format!("%{byte:02X}"),
        })
        .collect()
}

fn location<'a>(response: &'a LocalResponse<'_>) -> Option<&'a str> {
    response.headers().get_one("Location")
}

#[rocket::async_test]
async fn pages_redirect_to_login_without_session() {
    let (client, _, _) = setup().await;
    for uri in ["/admin", "/admin/devices", "/admin/sms-requests"] {
        let response = client.get(uri).dispatch().await;
        assert_eq!(response.status(), Status::SeeOther, "GET {uri}");
        assert_eq!(location(&response), Some("/admin/login"));
    }
    let response = client.post("/admin/heartbeat").dispatch().await;
    assert_eq!(location(&response), Some("/admin/login"));

    assert!(body(&client, "/admin/login").await.contains("key_file"));
}

#[rocket::async_test]
async fn login_rejects_unpaired_or_missing_key() {
    let (client, _, _) = setup().await;
    for pem in [test_keys::OTHER_PRIVATE_KEY, test_keys::PUBLIC_KEY, ""] {
        let response = login(&client, pem).await;
        assert_eq!(response.status(), Status::Ok);
        assert!(response
            .into_string()
            .await
            .unwrap()
            .contains("flash error"));
    }
    let response = client.get("/admin").dispatch().await;
    assert_eq!(location(&response), Some("/admin/login"));
}

#[rocket::async_test]
async fn paired_key_opens_every_page() {
    let (client, _, _) = setup().await;
    let response = login(&client, test_keys::PRIVATE_KEY).await;
    assert_eq!(location(&response), Some("/admin"));

    for uri in [
        "/admin",
        "/admin/devices",
        "/admin/devices/new",
        "/admin/sms-requests",
        "/admin/sms-requests?status=SENT&q=x&page=9",
        "/admin/sms-requests/new",
    ] {
        body(&client, uri).await;
    }
    let response = client.post("/admin/heartbeat").dispatch().await;
    assert_eq!(response.status(), Status::NoContent);
    let response = client.get("/admin/login").dispatch().await;
    assert_eq!(location(&response), Some("/admin"));
}

#[rocket::async_test]
async fn pasted_key_also_logs_in() {
    let (client, _, _) = setup().await;
    let response = post_form(
        &client,
        "/admin/login",
        &[("key_text", test_keys::PRIVATE_KEY)],
    )
    .await;
    assert_eq!(location(&response), Some("/admin"));
}

#[rocket::async_test]
async fn logout_ends_session() {
    let (client, _, _) = setup().await;
    login(&client, test_keys::PRIVATE_KEY).await;
    let csrf = csrf_token(&client).await;

    let response = post_form(&client, "/admin/logout", &[("csrf", &csrf)]).await;
    assert_eq!(location(&response), Some("/admin/login"));
    let response = client.get("/admin").dispatch().await;
    assert_eq!(location(&response), Some("/admin/login"));
}

#[rocket::async_test]
async fn form_posts_require_session_csrf_token() {
    let (client, _, _) = setup().await;
    login(&client, test_keys::PRIVATE_KEY).await;
    let wrong = [("csrf", "wrong")];

    let device_id = Uuid::new_v4().to_string();
    let response = post_form(
        &client,
        &format!("/admin/devices/{device_id}/delete"),
        &wrong,
    )
    .await;
    assert_eq!(response.status(), Status::Forbidden);
    let response = post_form(&client, "/admin/logout", &wrong).await;
    assert_eq!(response.status(), Status::Forbidden);
}

#[rocket::async_test]
async fn device_form_registers_numbers_and_output_is_escaped() {
    let (client, _, db) = setup().await;
    login(&client, test_keys::PRIVATE_KEY).await;
    let csrf = csrf_token(&client).await;

    // Rows keep their original indices after one is removed in the browser.
    let response = post_form(
        &client,
        "/admin/devices",
        &[
            ("csrf", &csrf),
            ("token", "token-a"),
            ("device_info", "<script>alert(1)</script>"),
            ("phone_numbers[0].phone_number", "010-1234-5678"),
            ("phone_numbers[0].daily_limit", "10"),
            ("phone_numbers[0].monthly_limit", "100"),
            ("phone_numbers[0].daily_sent_count", "3"),
            ("phone_numbers[0].monthly_sent_count", ""),
            ("phone_numbers[2].phone_number", "01099998888"),
            ("phone_numbers[2].daily_limit", "0"),
            ("phone_numbers[2].monthly_limit", "0"),
            ("phone_numbers[2].daily_sent_count", ""),
            ("phone_numbers[2].monthly_sent_count", ""),
        ],
    )
    .await;
    assert_eq!(location(&response), Some("/admin/devices"));

    let numbers = phone_number::Entity::find().all(&db).await.unwrap();
    assert_eq!(numbers.len(), 2);
    let today = Local::now().date_naive();
    let first = numbers
        .iter()
        .find(|row| row.phone_number == "01012345678")
        .unwrap();
    assert_eq!(first.daily_limit, 10);
    assert_eq!(first.effective_daily_sent_count(today), 3);
    assert_eq!(first.effective_monthly_sent_count(today), 0);

    let page = body(&client, "/admin/devices").await;
    assert!(page.contains("&lt;script&gt;alert(1)&lt;&#x2F;script&gt;"));
    assert!(!page.contains("<script>alert(1)"));

    let device_id = numbers[0].fcm_token_id;
    let edit = body(&client, &format!("/admin/devices/{device_id}/edit")).await;
    assert!(edit.contains("placeholder=\"현재 3\""));
}

#[rocket::async_test]
async fn failed_sms_request_keeps_form_values() {
    let (client, _, _) = setup().await;
    login(&client, test_keys::PRIVATE_KEY).await;
    let csrf = csrf_token(&client).await;

    let response = post_form(
        &client,
        "/admin/sms-requests",
        &[
            ("csrf", &csrf),
            ("phone_number", "01011112222"),
            ("message", "hello admin"),
            ("callback_url", ""),
        ],
    )
    .await;
    assert_eq!(response.status(), Status::Ok);
    let page = response.into_string().await.unwrap();
    assert!(page.contains("flash error"));
    assert!(page.contains("hello admin"));
}

#[rocket::async_test]
async fn report_form_finalizes_request() {
    let (client, schema, db) = setup().await;
    let response = schema
        .execute(
            r#"mutation { registerFcmToken(input: { token: "token-a", deviceInfo: "phone",
                phoneNumbers: [{ phoneNumber: "01012345678", dailyLimit: 10, monthlyLimit: 100 }] }) { id } }"#,
        )
        .await;
    assert!(response.errors.is_empty(), "{:?}", response.errors);
    let sender = phone_number::Entity::find()
        .filter(phone_number::Column::PhoneNumber.eq("01012345678"))
        .one(&db)
        .await
        .unwrap()
        .unwrap();
    let now = Local::now().naive_local();
    let request_id = Uuid::new_v4();
    sms_request::ActiveModel {
        id: Set(request_id),
        phone_number: Set("01099998888".to_string()),
        message: Set("hello".to_string()),
        callback_url: Set(None),
        callback_response_code: Set(None),
        callback_response_body: Set(None),
        status: Set(sms_request::SmsStatus::Sending),
        assigned_token_id: Set(Some(sender.fcm_token_id)),
        assigned_phone_number_id: Set(Some(sender.id)),
        error: Set(None),
        created_at: Set(now),
        updated_at: Set(now),
    }
    .insert(&db)
    .await
    .unwrap();

    login(&client, test_keys::PRIVATE_KEY).await;
    let csrf = csrf_token(&client).await;
    let detail_uri = format!("/admin/sms-requests/{request_id}");
    let response = post_form(
        &client,
        &format!("{detail_uri}/report"),
        &[
            ("csrf", &csrf),
            ("success", "false"),
            ("error", "no signal"),
            ("daily_sent_count", ""),
            ("monthly_sent_count", ""),
        ],
    )
    .await;
    assert_eq!(location(&response), Some(detail_uri.as_str()));

    let stored = sms_request::Entity::find_by_id(request_id)
        .one(&db)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored.status, sms_request::SmsStatus::Failed);
    assert_eq!(stored.error.as_deref(), Some("no signal"));

    let page = body(&client, &detail_uri).await;
    assert!(page.contains("no signal"));
    assert!(page.contains("01012345678"));
    assert!(body(&client, "/admin/sms-requests?status=FAILED")
        .await
        .contains(&request_id.to_string()));
}

#[rocket::async_test]
async fn unknown_sms_request_is_not_found() {
    let (client, _, _) = setup().await;
    login(&client, test_keys::PRIVATE_KEY).await;
    for id in ["not-a-uuid".to_string(), Uuid::new_v4().to_string()] {
        let response = client
            .get(format!("/admin/sms-requests/{id}"))
            .dispatch()
            .await;
        assert_eq!(response.status(), Status::NotFound);
    }
}

#[test]
fn device_is_active_within_token_window() {
    let now = Local::now().naive_local();
    assert!(is_active(&(now - Duration::days(1)).to_string(), now));
    assert!(!is_active(&(now - Duration::days(91)).to_string(), now));
    assert!(!is_active("garbage", now));
}

#[test]
fn quota_treats_null_remaining_as_unlimited() {
    assert!(has_quota(
        &json!({ "dailyRemaining": null, "monthlyRemaining": 5 })
    ));
    assert!(!has_quota(
        &json!({ "dailyRemaining": 0, "monthlyRemaining": null })
    ));
}
