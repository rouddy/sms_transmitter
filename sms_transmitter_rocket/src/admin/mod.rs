//! Server-rendered admin pages (Tera) covering every GraphQL query and mutation.
//!
//! Pages run GraphQL in-process against the same schema as `/graphql`, so they behave
//! exactly like API calls. Every page except the login requires an admin session; see
//! `session.rs`.

use async_graphql::Variables;
use chrono::{Local, NaiveDateTime};
use rocket::fairing::AdHoc;
use rocket::form::Form;
use rocket::http::{CookieJar, Status};
use rocket::request::FlashMessage;
use rocket::response::{self, Redirect, Responder};
use rocket::{Either, Request, Response, State};
use rocket_dyn_templates::Template;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::IpAddr;

mod devices;
mod session;
mod sms_requests;

use session::{AdminSession, AdminSessions, HEARTBEAT_INTERVAL};

use crate::auth::JwtVerifier;
use crate::graphql::sms_request::ACTIVE_TOKEN_WINDOW;
use crate::graphql::AppSchema;

/// Mounts the admin pages at `/admin`. Expects `AppSchema` and `JwtVerifier` to be managed.
pub fn stage() -> AdHoc {
    AdHoc::on_ignite("Admin pages", |rocket| async {
        rocket
            .manage(AdminSessions::default())
            .attach(Template::fairing())
            .mount(
                "/admin",
                routes![
                    login_page,
                    login,
                    logout,
                    heartbeat,
                    overview,
                    devices::list,
                    devices::new,
                    devices::edit,
                    devices::save,
                    devices::delete,
                    sms_requests::list,
                    sms_requests::new,
                    sms_requests::create,
                    sms_requests::detail,
                    sms_requests::report,
                ],
            )
            .register("/admin", catchers![unauthorized])
    })
}

#[catch(401)]
fn unauthorized() -> Redirect {
    Redirect::to("/admin/login")
}

#[derive(FromForm)]
struct LoginForm {
    key_file: Option<String>,
    key_text: Option<String>,
}

#[derive(FromForm)]
struct CsrfForm {
    csrf: String,
}

#[get("/login")]
fn login_page(session: Option<AdminSession>) -> Either<Redirect, Template> {
    match session {
        Some(_) => Either::Left(Redirect::to("/admin")),
        None => Either::Right(login_template(None)),
    }
}

#[post("/login", data = "<form>")]
fn login(
    form: Form<LoginForm>,
    verifier: &State<JwtVerifier>,
    sessions: &State<AdminSessions>,
    cookies: &CookieJar<'_>,
    client_ip: Option<IpAddr>,
) -> Either<Redirect, Template> {
    let pem = [&form.key_file, &form.key_text]
        .into_iter()
        .flatten()
        .map(|pem| pem.trim())
        .find(|pem| !pem.is_empty());
    let Some(pem) = pem else {
        return Either::Right(login_template(Some(
            "개인키 파일을 선택하거나 PEM 내용을 붙여 넣으세요.",
        )));
    };
    if let Err(reason) = verifier.accepts_private_key(pem.as_bytes()) {
        tracing::warn!("Rejected admin login from {client_ip:?}: {reason}");
        return Either::Right(login_template(Some(
            "서버의 공개키와 짝이 맞는 ES256 개인키(PKCS#8 PEM)가 아닙니다.",
        )));
    }

    cookies.add(session::session_cookie(sessions.create()));
    tracing::info!("Admin login from {client_ip:?}");
    Either::Left(Redirect::to("/admin"))
}

fn login_template(error: Option<&str>) -> Template {
    Template::render("admin/login", json!({ "error": error }))
}

#[post("/logout", data = "<form>")]
fn logout(
    session: AdminSession,
    form: Form<CsrfForm>,
    sessions: &State<AdminSessions>,
    cookies: &CookieJar<'_>,
) -> Result<Redirect, Status> {
    session.check_csrf(&form.csrf)?;
    sessions.remove(&session.id);
    cookies.remove(session::removal_cookie());
    Ok(Redirect::to("/admin/login"))
}

#[post("/heartbeat")]
fn heartbeat(_session: AdminSession) -> Status {
    Status::NoContent
}

const OVERVIEW_QUERY: &str = "{
  fcmTokens { id deviceInfo lastAccessTime }
  phoneNumbers {
    id fcmTokenId phoneNumber dailyLimit monthlyLimit
    dailySentCount monthlySentCount dailyRemaining monthlyRemaining
  }
  smsRequests { id phoneNumber message status createdAt }
}";

const RECENT_REQUESTS: usize = 10;

#[get("/")]
async fn overview(
    session: AdminSession,
    flash: Option<FlashMessage<'_>>,
    schema: &State<AppSchema>,
) -> Result<Template, PageError> {
    let data = execute(schema, OVERVIEW_QUERY, json!({})).await?;
    let now = Local::now().naive_local();

    let devices: HashMap<&str, (&str, bool)> = items(&data["fcmTokens"])
        .iter()
        .map(|device| {
            let active = is_active(str_field(device, "lastAccessTime"), now);
            (
                str_field(device, "id"),
                (str_field(device, "deviceInfo"), active),
            )
        })
        .collect();

    let mut phone_numbers: Vec<Value> = items(&data["phoneNumbers"]).to_vec();
    for phone_number in &mut phone_numbers {
        let (device_info, device_active) = devices
            .get(str_field(phone_number, "fcmTokenId"))
            .copied()
            .unwrap_or_default();
        phone_number["deviceInfo"] = device_info.into();
        phone_number["deviceActive"] = device_active.into();
        phone_number["hasQuota"] = has_quota(phone_number).into();
    }
    phone_numbers.sort_by(|a, b| str_field(a, "phoneNumber").cmp(str_field(b, "phoneNumber")));
    let sendable_count = phone_numbers
        .iter()
        .filter(|phone_number| {
            phone_number["deviceActive"] == true && phone_number["hasQuota"] == true
        })
        .count();

    let status_counts = count_by_status(items(&data["smsRequests"]));
    let mut requests: Vec<Value> = items(&data["smsRequests"]).to_vec();
    sort_newest_first(&mut requests);
    let request_count = requests.len();
    requests.truncate(RECENT_REQUESTS);

    Ok(page(
        "admin/overview",
        "overview",
        &session,
        flash,
        json!({
            "device_count": devices.len(),
            "active_device_count": devices.values().filter(|(_, active)| *active).count(),
            "phone_numbers": phone_numbers,
            "sendable_count": sendable_count,
            "request_count": request_count,
            "status_counts": status_counts,
            "recent_requests": requests,
        }),
    ))
}

/// Runs a GraphQL operation in-process against the `/graphql` schema. Errors are joined
/// into one message for display.
async fn execute(schema: &AppSchema, query: &str, variables: Value) -> Result<Value, String> {
    let request = async_graphql::Request::new(query).variables(Variables::from_json(variables));
    let response = schema.execute(request).await;
    if !response.errors.is_empty() {
        let messages: Vec<String> = response.errors.into_iter().map(|e| e.message).collect();
        return Err(messages.join("; "));
    }
    response.data.into_json().map_err(|e| e.to_string())
}

/// Renders an admin page with the values the layout needs; `active` names the nav item.
fn page(
    name: &'static str,
    active: &'static str,
    session: &AdminSession,
    flash: Option<FlashMessage<'_>>,
    mut context: Value,
) -> Template {
    context["active"] = active.into();
    context["csrf"] = session.csrf_token.clone().into();
    context["heartbeat_ms"] = heartbeat_ms().into();
    if let Some(flash) = flash {
        context["flash"] = json!({ "kind": flash.kind(), "message": flash.message() });
    }
    Template::render(name, context)
}

fn heartbeat_ms() -> u64 {
    HEARTBEAT_INTERVAL.as_millis() as u64
}

/// Error page for failed queries, unknown IDs and rejected form posts.
struct PageError {
    status: Status,
    message: String,
}

impl PageError {
    fn not_found(message: &str) -> Self {
        Self {
            status: Status::NotFound,
            message: message.to_string(),
        }
    }
}

impl From<String> for PageError {
    fn from(message: String) -> Self {
        Self {
            status: Status::InternalServerError,
            message,
        }
    }
}

impl From<Status> for PageError {
    fn from(status: Status) -> Self {
        Self {
            status,
            message: format!(
                "요청이 거부되었습니다 ({status}). 페이지를 새로 고친 뒤 다시 시도하세요."
            ),
        }
    }
}

impl<'r> Responder<'r, 'static> for PageError {
    fn respond_to(self, request: &'r Request<'_>) -> response::Result<'static> {
        let template = Template::render(
            "admin/error",
            json!({ "active": "", "message": self.message, "heartbeat_ms": heartbeat_ms() }),
        );
        Response::build_from(template.respond_to(request)?)
            .status(self.status)
            .ok()
    }
}

/// `SentCountsInput` for counts typed by the operator, dated today like a device report.
/// A blank field counts as 0, which never lowers the stored count.
fn sent_counts_input(daily: Option<i32>, monthly: Option<i32>) -> Value {
    if daily.is_none() && monthly.is_none() {
        return Value::Null;
    }
    json!({
        "countedOn": Local::now().date_naive().to_string(),
        "dailySentCount": daily.unwrap_or(0),
        "monthlySentCount": monthly.unwrap_or(0),
    })
}

fn items(value: &Value) -> &[Value] {
    value.as_array().map(Vec::as_slice).unwrap_or_default()
}

fn str_field<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().unwrap_or_default()
}

/// Whether `requestSms` may pick this device: it was seen within `ACTIVE_TOKEN_WINDOW`.
/// `last_access_time` is the GraphQL string form of a `NaiveDateTime`.
fn is_active(last_access_time: &str, now: NaiveDateTime) -> bool {
    NaiveDateTime::parse_from_str(last_access_time, "%Y-%m-%d %H:%M:%S%.f")
        .is_ok_and(|seen| seen > now - ACTIVE_TOKEN_WINDOW)
}

/// Mirrors `phone_number::Model::can_send_now`; a null remaining count means unlimited.
fn has_quota(phone_number: &Value) -> bool {
    ["dailyRemaining", "monthlyRemaining"].iter().all(|key| {
        phone_number[*key]
            .as_i64()
            .is_none_or(|remaining| remaining > 0)
    })
}

fn count_by_status(requests: &[Value]) -> HashMap<&str, usize> {
    let mut counts = HashMap::from([("SENDING", 0), ("SENT", 0), ("FAILED", 0)]);
    for request in requests {
        *counts.entry(str_field(request, "status")).or_default() += 1;
    }
    counts
}

/// `createdAt` strings share one fixed-width prefix, so they sort chronologically.
fn sort_newest_first(requests: &mut [Value]) {
    requests.sort_by(|a, b| str_field(b, "createdAt").cmp(str_field(a, "createdAt")));
}

#[cfg(test)]
mod tests;
