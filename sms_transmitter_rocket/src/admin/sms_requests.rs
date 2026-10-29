//! SMS requests: list (`smsRequests`), detail (`smsRequest`), send (`requestSms`) and manual
//! result reports (`reportSmsResult`).

use rocket::form::Form;
use rocket::request::FlashMessage;
use rocket::response::{Flash, Redirect};
use rocket::{Either, State};
use rocket_dyn_templates::Template;
use serde_json::{json, Value};
use std::collections::HashMap;
use uuid::Uuid;

use super::session::AdminSession;
use super::{
    count_by_status, execute, items, page, sent_counts_input, sort_newest_first, str_field,
    PageError,
};
use crate::graphql::AppSchema;

const PAGE_SIZE: usize = 50;

const LIST_QUERY: &str = "{
  smsRequests {
    id phoneNumber message status assignedPhoneNumberId error callbackUrl
    callbackResponseCode createdAt
  }
  phoneNumbers { id phoneNumber }
}";

const DETAIL_QUERY: &str = "query($id: ID!) {
  smsRequest(id: $id) {
    id phoneNumber message callbackUrl callbackResponseCode callbackResponseBody status
    assignedDeviceId assignedPhoneNumberId error createdAt updatedAt
  }
  fcmTokens { id deviceInfo }
  phoneNumbers { id phoneNumber }
}";

const REQUEST_MUTATION: &str =
    "mutation($input: RequestSmsInput!) { requestSms(input: $input) { id } }";

const REPORT_MUTATION: &str =
    "mutation($input: ReportSmsResultInput!) { reportSmsResult(input: $input) }";

#[derive(FromForm)]
pub(super) struct SmsForm {
    csrf: String,
    phone_number: String,
    message: String,
    callback_url: String,
}

#[derive(FromForm)]
pub(super) struct ReportForm {
    csrf: String,
    success: bool,
    error: String,
    daily_sent_count: Option<i32>,
    monthly_sent_count: Option<i32>,
}

#[get("/sms-requests?<status>&<q>&<page>")]
pub(super) async fn list(
    session: AdminSession,
    flash: Option<FlashMessage<'_>>,
    schema: &State<AppSchema>,
    status: Option<&str>,
    q: Option<&str>,
    page: Option<usize>,
) -> Result<Template, PageError> {
    let data = execute(schema, LIST_QUERY, json!({})).await?;
    let senders = phone_numbers_by_id(&data);
    let status = status.unwrap_or_default();
    let q = q.unwrap_or_default().trim();
    let needle = q.to_lowercase();

    let all_requests = items(&data["smsRequests"]);
    let mut requests: Vec<Value> = all_requests
        .iter()
        .filter(|request| status.is_empty() || str_field(request, "status") == status)
        .filter(|request| {
            needle.is_empty()
                || ["id", "phoneNumber", "message"]
                    .iter()
                    .any(|key| str_field(request, key).to_lowercase().contains(&needle))
        })
        .cloned()
        .collect();
    sort_newest_first(&mut requests);

    let total = requests.len();
    let pages = total.div_ceil(PAGE_SIZE).max(1);
    let current_page = page.unwrap_or(1).clamp(1, pages);
    let mut rows: Vec<Value> = requests
        .into_iter()
        .skip((current_page - 1) * PAGE_SIZE)
        .take(PAGE_SIZE)
        .collect();
    for row in &mut rows {
        row["sender"] = json!(senders.get(str_field(row, "assignedPhoneNumberId")));
    }

    // `page` is shadowed by the query parameter here.
    Ok(super::page(
        "admin/sms_requests",
        "sms",
        &session,
        flash,
        json!({
            "requests": rows,
            "total": total,
            "page": current_page,
            "pages": pages,
            "status": status,
            "q": q,
            "status_counts": count_by_status(all_requests),
            "all_count": all_requests.len(),
        }),
    ))
}

#[get("/sms-requests/new")]
pub(super) fn new(session: AdminSession) -> Template {
    let values = json!({ "phoneNumber": "", "message": "", "callbackUrl": "" });
    new_form_page(&session, values, None)
}

#[post("/sms-requests", data = "<form>")]
pub(super) async fn create(
    session: AdminSession,
    schema: &State<AppSchema>,
    form: Form<SmsForm>,
) -> Result<Either<Flash<Redirect>, Template>, PageError> {
    session.check_csrf(&form.csrf)?;
    let phone_number = form.phone_number.trim();
    // Browsers submit textarea line breaks as CRLF.
    let message = form.message.replace("\r\n", "\n");
    let callback_url = form.callback_url.trim();
    let variables = json!({ "input": {
        "phoneNumber": phone_number,
        "message": message,
        "callbackUrl": (!callback_url.is_empty()).then_some(callback_url),
    }});

    match execute(schema, REQUEST_MUTATION, variables).await {
        Ok(data) => Ok(Either::Left(Flash::success(
            Redirect::to(format!(
                "/admin/sms-requests/{}",
                str_field(&data["requestSms"], "id")
            )),
            "SMS 요청을 보냈습니다.",
        ))),
        Err(error) => {
            let values = json!({
                "phoneNumber": phone_number,
                "message": message,
                "callbackUrl": callback_url,
            });
            Ok(Either::Right(new_form_page(&session, values, Some(error))))
        }
    }
}

#[get("/sms-requests/<id>")]
pub(super) async fn detail(
    session: AdminSession,
    flash: Option<FlashMessage<'_>>,
    schema: &State<AppSchema>,
    id: &str,
) -> Result<Template, PageError> {
    let id = parse_request_id(id)?;
    let data = execute(schema, DETAIL_QUERY, json!({ "id": id.to_string() })).await?;
    let request = &data["smsRequest"];
    if request.is_null() {
        return Err(not_found());
    }
    let device = items(&data["fcmTokens"])
        .iter()
        .find(|device| str_field(device, "id") == str_field(request, "assignedDeviceId"))
        .map(|device| str_field(device, "deviceInfo"));
    let sender = phone_numbers_by_id(&data)
        .get(str_field(request, "assignedPhoneNumberId"))
        .copied();

    Ok(page(
        "admin/sms_request",
        "sms",
        &session,
        flash,
        json!({ "request": request, "device": device, "sender": sender }),
    ))
}

#[post("/sms-requests/<id>/report", data = "<form>")]
pub(super) async fn report(
    session: AdminSession,
    schema: &State<AppSchema>,
    id: &str,
    form: Form<ReportForm>,
) -> Result<Flash<Redirect>, PageError> {
    session.check_csrf(&form.csrf)?;
    let id = parse_request_id(id)?;
    let error = form.error.trim();
    let variables = json!({ "input": {
        "requestId": id.to_string(),
        "success": form.success,
        "error": (!form.success && !error.is_empty()).then_some(error),
        "sentCounts": sent_counts_input(form.daily_sent_count, form.monthly_sent_count),
    }});

    let redirect = Redirect::to(format!("/admin/sms-requests/{id}"));
    Ok(match execute(schema, REPORT_MUTATION, variables).await {
        Ok(_) => Flash::success(redirect, "결과를 보고했습니다."),
        Err(error) => Flash::error(redirect, error),
    })
}

fn new_form_page(session: &AdminSession, values: Value, error: Option<String>) -> Template {
    page(
        "admin/sms_request_new",
        "sms",
        session,
        None,
        json!({ "values": values, "error": error }),
    )
}

fn phone_numbers_by_id(data: &Value) -> HashMap<&str, &str> {
    items(&data["phoneNumbers"])
        .iter()
        .map(|phone_number| {
            (
                str_field(phone_number, "id"),
                str_field(phone_number, "phoneNumber"),
            )
        })
        .collect()
}

fn parse_request_id(id: &str) -> Result<Uuid, PageError> {
    Uuid::parse_str(id).map_err(|_| not_found())
}

fn not_found() -> PageError {
    PageError::not_found("SMS 요청을 찾을 수 없습니다.")
}
