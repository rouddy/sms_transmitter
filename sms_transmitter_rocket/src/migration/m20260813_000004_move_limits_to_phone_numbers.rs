use sea_orm::ConnectionTrait;
use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20260813_000004_move_limits_to_phone_numbers"
    }
}

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .get_connection()
            .execute_unprepared(
                r#"
                PRAGMA foreign_keys = OFF;

                CREATE TABLE fcm_tokens_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    token TEXT NOT NULL UNIQUE,
                    device_info TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                INSERT INTO fcm_tokens_new (id, token, device_info, created_at, updated_at)
                SELECT id, token, device_info, created_at, updated_at
                FROM fcm_tokens;

                CREATE TABLE phone_numbers_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    fcm_token_id TEXT NOT NULL,
                    phone_number TEXT NOT NULL,
                    daily_limit INTEGER NOT NULL DEFAULT 0,
                    monthly_limit INTEGER NOT NULL DEFAULT 0,
                    daily_sent_count INTEGER NOT NULL DEFAULT 0,
                    monthly_sent_count INTEGER NOT NULL DEFAULT 0,
                    last_daily_reset TEXT NOT NULL,
                    last_monthly_reset TEXT NOT NULL,
                    FOREIGN KEY (fcm_token_id) REFERENCES fcm_tokens_new(id) ON DELETE CASCADE
                );

                INSERT INTO phone_numbers_new (
                    id,
                    fcm_token_id,
                    phone_number,
                    daily_limit,
                    monthly_limit,
                    daily_sent_count,
                    monthly_sent_count,
                    last_daily_reset,
                    last_monthly_reset
                )
                SELECT
                    p.id,
                    p.fcm_token_id,
                    p.phone_number,
                    f.daily_limit,
                    f.monthly_limit,
                    f.daily_sent_count,
                    f.monthly_sent_count,
                    f.last_daily_reset,
                    f.last_monthly_reset
                FROM phone_numbers p
                INNER JOIN fcm_tokens f ON f.id = p.fcm_token_id;

                CREATE TABLE sms_requests_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    phone_number TEXT NOT NULL,
                    message TEXT NOT NULL,
                    callback_url TEXT NULL,
                    status TEXT NOT NULL,
                    assigned_token_id TEXT NULL,
                    assigned_phone_number_id TEXT NULL,
                    error TEXT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    callback_response_code INTEGER NULL,
                    callback_response_body TEXT NULL,
                    FOREIGN KEY (assigned_token_id) REFERENCES fcm_tokens_new(id) ON DELETE SET NULL,
                    FOREIGN KEY (assigned_phone_number_id) REFERENCES phone_numbers_new(id) ON DELETE SET NULL
                );

                INSERT INTO sms_requests_new (
                    id,
                    phone_number,
                    message,
                    callback_url,
                    status,
                    assigned_token_id,
                    assigned_phone_number_id,
                    error,
                    created_at,
                    updated_at,
                    callback_response_code,
                    callback_response_body
                )
                SELECT
                    id,
                    phone_number,
                    message,
                    callback_url,
                    status,
                    assigned_token_id,
                    NULL,
                    error,
                    created_at,
                    updated_at,
                    callback_response_code,
                    callback_response_body
                FROM sms_requests;

                DROP TABLE sms_requests;
                DROP TABLE phone_numbers;
                DROP TABLE fcm_tokens;

                ALTER TABLE fcm_tokens_new RENAME TO fcm_tokens;
                ALTER TABLE phone_numbers_new RENAME TO phone_numbers;
                ALTER TABLE sms_requests_new RENAME TO sms_requests;

                CREATE UNIQUE INDEX idx_phone_numbers_fcm_token_id_phone_number
                    ON phone_numbers (fcm_token_id, phone_number);

                PRAGMA foreign_keys = ON;
                "#,
            )
            .await
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .get_connection()
            .execute_unprepared(
                r#"
                PRAGMA foreign_keys = OFF;

                CREATE TABLE fcm_tokens_old (
                    id TEXT NOT NULL PRIMARY KEY,
                    token TEXT NOT NULL UNIQUE,
                    device_info TEXT NOT NULL,
                    daily_limit INTEGER NOT NULL,
                    monthly_limit INTEGER NOT NULL,
                    daily_sent_count INTEGER NOT NULL DEFAULT 0,
                    monthly_sent_count INTEGER NOT NULL DEFAULT 0,
                    last_daily_reset TEXT NOT NULL,
                    last_monthly_reset TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                INSERT INTO fcm_tokens_old (
                    id,
                    token,
                    device_info,
                    daily_limit,
                    monthly_limit,
                    daily_sent_count,
                    monthly_sent_count,
                    last_daily_reset,
                    last_monthly_reset,
                    created_at,
                    updated_at
                )
                SELECT
                    f.id,
                    f.token,
                    f.device_info,
                    COALESCE((
                        SELECT pn.daily_limit
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), 0),
                    COALESCE((
                        SELECT pn.monthly_limit
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), 0),
                    COALESCE((
                        SELECT pn.daily_sent_count
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), 0),
                    COALESCE((
                        SELECT pn.monthly_sent_count
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), 0),
                    COALESCE((
                        SELECT pn.last_daily_reset
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), DATE('now')),
                    COALESCE((
                        SELECT pn.last_monthly_reset
                        FROM phone_numbers pn
                        WHERE pn.fcm_token_id = f.id
                        ORDER BY pn.phone_number
                        LIMIT 1
                    ), DATE('now')),
                    f.created_at,
                    f.updated_at
                FROM fcm_tokens f;

                CREATE TABLE phone_numbers_old (
                    id TEXT NOT NULL PRIMARY KEY,
                    fcm_token_id TEXT NOT NULL,
                    phone_number TEXT NOT NULL,
                    FOREIGN KEY (fcm_token_id) REFERENCES fcm_tokens_old(id) ON DELETE CASCADE
                );

                INSERT INTO phone_numbers_old (id, fcm_token_id, phone_number)
                SELECT id, fcm_token_id, phone_number
                FROM phone_numbers;

                CREATE TABLE sms_requests_old (
                    id TEXT NOT NULL PRIMARY KEY,
                    phone_number TEXT NOT NULL,
                    message TEXT NOT NULL,
                    callback_url TEXT NULL,
                    status TEXT NOT NULL,
                    assigned_token_id TEXT NULL,
                    error TEXT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    callback_response_code INTEGER NULL,
                    callback_response_body TEXT NULL,
                    FOREIGN KEY (assigned_token_id) REFERENCES fcm_tokens_old(id) ON DELETE SET NULL
                );

                INSERT INTO sms_requests_old (
                    id,
                    phone_number,
                    message,
                    callback_url,
                    status,
                    assigned_token_id,
                    error,
                    created_at,
                    updated_at,
                    callback_response_code,
                    callback_response_body
                )
                SELECT
                    id,
                    phone_number,
                    message,
                    callback_url,
                    status,
                    assigned_token_id,
                    error,
                    created_at,
                    updated_at,
                    callback_response_code,
                    callback_response_body
                FROM sms_requests;

                DROP TABLE sms_requests;
                DROP TABLE phone_numbers;
                DROP TABLE fcm_tokens;

                ALTER TABLE fcm_tokens_old RENAME TO fcm_tokens;
                ALTER TABLE phone_numbers_old RENAME TO phone_numbers;
                ALTER TABLE sms_requests_old RENAME TO sms_requests;

                CREATE UNIQUE INDEX idx_phone_numbers_fcm_token_id_phone_number
                    ON phone_numbers (fcm_token_id, phone_number);

                PRAGMA foreign_keys = ON;
                "#,
            )
            .await
    }
}
