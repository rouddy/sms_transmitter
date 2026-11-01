//! Devices (`fcmTokens`): list, register/update (`registerFcmToken`), delete (`deleteFcmToken`).

use chrono::Local;
use rocket::form::Form;
use rocket::request::FlashMessage;
use rocket::response::{Flash, Redirect};
use rocket::{Either, State};
use rocket_dyn_templates::Template;
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;

use super::session::AdminSession;
use super::{execute, is_active, items, page, sent_counts_input, str_field, CsrfForm, PageError};
use crate::graphql::AppSchema;

const DEVICES_QUERY: &str = "{
  fcmTokens {
    id token deviceInfo createdAt updatedAt lastAccessTime
    phoneNumbers {
      id phoneNumber dailyLimit monthlyLimit dailySentCount monthlySentCount
      dailyRemaining monthlyRemaining lastDailyReset lastMonthlyReset
    }
  }
}";

const REGISTER_MUTATION: &str =
    "mutation($input: RegisterFcmTokenInput!) { registerFcmToken(input: $input) { id } }";

const DELETE_MUTATION: &str = "mutation($id: ID!) { deleteFcmToken(tokenId: $id) }";

#[derive(FromForm)]
pub(super) struct DeviceForm {
    csrf: String,
    token: String,
    device_info: String,
    phone_numbers: Vec<PhoneNumberRow>,
}

#[derive(FromForm, Serialize, Default)]
#[serde(rename_all = "camelCase")]
struct PhoneNumberRow {
    phone_number: String,
    daily_limit: i32,
    monthly_limit: i32,
    /// Counts to report like a device does; blank means none.
    daily_sent_count: Option<i32>,
    monthly_sent_count: Option<i32>,
}

/// What the device form shows: a stored device, or what the operator submitted.
#[derive(Default)]
struct DeviceValues {
    token: String,
    device_info: String,
    phone_numbers: Vec<PhoneNumberRow>,
}

#[get("/devices")]
pub(super) async fn list(
    session: AdminSession,
    flash: Option<FlashMessage<'_>>,
    schema: &State<AppSchema>,
) -> Result<Template, PageError> {
    let devices = load_devices(schema).await?;
    Ok(page(
        "admin/devices",
        "devices",
        &session,
        flash,
        json!({ "devices": devices }),
    ))
}

#[get("/devices/new")]
pub(super) async fn new(
    session: AdminSession,
    schema: &State<AppSchema>,
) -> Result<Template, PageError> {
    let devices = load_devices(schema).await?;
    let values = DeviceValues {
        phone_numbers: vec![PhoneNumberRow::default()],
        ..Default::default()
    };
    Ok(form_page(&session, &devices, values, None))
}

#[get("/devices/<id>/edit")]
pub(super) async fn edit(
    session: AdminSession,
    schema: &State<AppSchema>,
    id: &str,
) -> Result<Template, PageError> {
    let devices = load_devices(schema).await?;
    let device = devices
        .iter()
        .find(|device| str_field(device, "id") == id)
        .ok_or_else(|| PageError::not_found("기기를 찾을 수 없습니다."))?;
    let values = DeviceValues {
        token: str_field(device, "token").to_string(),
        device_info: str_field(device, "deviceInfo").to_string(),
        phone_numbers: items(&device["phoneNumbers"])
            .iter()
            .map(|phone_number| PhoneNumberRow {
                phone_number: str_field(phone_number, "phoneNumber").to_string(),
                daily_limit: phone_number["dailyLimit"].as_i64().unwrap_or_default() as i32,
                monthly_limit: phone_number["monthlyLimit"].as_i64().unwrap_or_default() as i32,
                daily_sent_count: None,
                monthly_sent_count: None,
            })
            .collect(),
    };
    Ok(form_page(&session, &devices, values, None))
}

#[post("/devices", data = "<form>")]
pub(super) async fn save(
    session: AdminSession,
    schema: &State<AppSchema>,
    form: Form<DeviceForm>,
) -> Result<Either<Flash<Redirect>, Template>, PageError> {
    session.check_csrf(&form.csrf)?;
    let form = form.into_inner();
    let values = DeviceValues {
        token: form.token.trim().to_string(),
        device_info: form.device_info.trim().to_string(),
        phone_numbers: form.phone_numbers,
    };

    let result = if values.token.is_empty() {
        Err("FCM 토큰을 입력하세요.".to_string())
    } else {
        let phone_numbers: Vec<Value> = values
            .phone_numbers
            .iter()
            .map(|row| {
                json!({
                    "phoneNumber": row.phone_number,
                    "dailyLimit": row.daily_limit,
                    "monthlyLimit": row.monthly_limit,
                    "sentCounts": sent_counts_input(row.daily_sent_count, row.monthly_sent_count),
                })
            })
            .collect();
        let variables = json!({ "input": {
            "token": values.token,
            "deviceInfo": values.device_info,
            "phoneNumbers": phone_numbers,
        }});
        execute(schema, REGISTER_MUTATION, variables).await
    };

    match result {
        Ok(_) => Ok(Either::Left(Flash::success(
            Redirect::to("/admin/devices"),
            "기기를 저장했습니다.",
        ))),
        Err(error) => {
            let devices = load_devices(schema).await?;
            Ok(Either::Right(form_page(
                &session,
                &devices,
                values,
                Some(error),
            )))
        }
    }
}

#[post("/devices/<id>/delete", data = "<form>")]
pub(super) async fn delete(
    session: AdminSession,
    schema: &State<AppSchema>,
    id: &str,
    form: Form<CsrfForm>,
) -> Result<Flash<Redirect>, PageError> {
    session.check_csrf(&form.csrf)?;
    let redirect = Redirect::to("/admin/devices");
    Ok(
        match execute(schema, DELETE_MUTATION, json!({ "id": id })).await {
            Ok(data) if data["deleteFcmToken"] == true => {
                Flash::success(redirect, "기기를 삭제했습니다.")
            }
            Ok(_) => Flash::error(redirect, "이미 삭제된 기기입니다."),
            Err(error) => Flash::error(redirect, error),
        },
    )
}

/// All devices, most recently seen first, each with an `active` flag.
async fn load_devices(schema: &AppSchema) -> Result<Vec<Value>, PageError> {
    let data = execute(schema, DEVICES_QUERY, json!({})).await?;
    let now = Local::now().naive_local();
    let mut devices = items(&data["fcmTokens"]).to_vec();
    for device in &mut devices {
        device["active"] = is_active(str_field(device, "lastAccessTime"), now).into();
    }
    devices.sort_by(|a, b| str_field(b, "lastAccessTime").cmp(str_field(a, "lastAccessTime")));
    Ok(devices)
}

/// The register form. A token that is already registered edits that device, since
/// `registerFcmToken` upserts by token; its current sent counts are shown as hints.
fn form_page(
    session: &AdminSession,
    devices: &[Value],
    values: DeviceValues,
    error: Option<String>,
) -> Template {
    let existing = devices
        .iter()
        .find(|device| str_field(device, "token") == values.token);
    let current: HashMap<&str, &Value> = existing
        .map(|device| {
            items(&device["phoneNumbers"])
                .iter()
                .map(|phone_number| (str_field(phone_number, "phoneNumber"), phone_number))
                .collect()
        })
        .unwrap_or_default();
    let rows: Vec<Value> = values
        .phone_numbers
        .iter()
        .map(|row| {
            let mut view = json!(row);
            if let Some(stored) = current.get(row.phone_number.as_str()) {
                view["currentDaily"] = stored["dailySentCount"].clone();
                view["currentMonthly"] = stored["monthlySentCount"].clone();
            }
            view
        })
        .collect();

    page(
        "admin/device_form",
        "devices",
        session,
        None,
        json!({
            "existing": existing,
            "token": values.token,
            "device_info": values.device_info,
            "rows": rows,
            "empty_row": PhoneNumberRow::default(),
            "error": error,
        }),
    )
}
