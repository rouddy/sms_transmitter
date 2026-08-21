use sea_orm_migration::prelude::*;

mod m20240101_000001_create_tables;
mod m20260716_000002_add_callback_response_to_sms_requests;
mod m20260812_000003_create_phone_numbers_table;
mod m20260813_000004_move_limits_to_phone_numbers;
mod m20260821_000005_unique_phone_number;
mod m20260821_000006_add_last_access_time_to_fcm_tokens;

pub struct Migrator;

#[async_trait::async_trait]
impl MigratorTrait for Migrator {
    fn migrations() -> Vec<Box<dyn MigrationTrait>> {
        vec![
            Box::new(m20240101_000001_create_tables::Migration),
            Box::new(m20260716_000002_add_callback_response_to_sms_requests::Migration),
            Box::new(m20260812_000003_create_phone_numbers_table::Migration),
            Box::new(m20260813_000004_move_limits_to_phone_numbers::Migration),
            Box::new(m20260821_000005_unique_phone_number::Migration),
            Box::new(m20260821_000006_add_last_access_time_to_fcm_tokens::Migration),
        ]
    }
}
