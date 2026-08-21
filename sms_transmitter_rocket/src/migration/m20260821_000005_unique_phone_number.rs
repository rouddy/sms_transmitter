use sea_orm::ConnectionTrait;
use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20260821_000005_unique_phone_number"
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

                CREATE TABLE phone_numbers_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    fcm_token_id TEXT NOT NULL,
                    phone_number TEXT NOT NULL UNIQUE,
                    daily_limit INTEGER NOT NULL DEFAULT 0,
                    monthly_limit INTEGER NOT NULL DEFAULT 0,
                    daily_sent_count INTEGER NOT NULL DEFAULT 0,
                    monthly_sent_count INTEGER NOT NULL DEFAULT 0,
                    last_daily_reset TEXT NOT NULL,
                    last_monthly_reset TEXT NOT NULL,
                    FOREIGN KEY (fcm_token_id) REFERENCES fcm_tokens(id) ON DELETE CASCADE
                );

                INSERT OR IGNORE INTO phone_numbers_new (
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
                    id,
                    fcm_token_id,
                    phone_number,
                    daily_limit,
                    monthly_limit,
                    daily_sent_count,
                    monthly_sent_count,
                    last_daily_reset,
                    last_monthly_reset
                FROM phone_numbers;

                DROP TABLE phone_numbers;
                ALTER TABLE phone_numbers_new RENAME TO phone_numbers;

                PRAGMA foreign_keys = ON;
                "#,
            )
            .await?;
        Ok(())
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .get_connection()
            .execute_unprepared(
                r#"
                PRAGMA foreign_keys = OFF;

                CREATE TABLE phone_numbers_old (
                    id TEXT NOT NULL PRIMARY KEY,
                    fcm_token_id TEXT NOT NULL,
                    phone_number TEXT NOT NULL,
                    daily_limit INTEGER NOT NULL DEFAULT 0,
                    monthly_limit INTEGER NOT NULL DEFAULT 0,
                    daily_sent_count INTEGER NOT NULL DEFAULT 0,
                    monthly_sent_count INTEGER NOT NULL DEFAULT 0,
                    last_daily_reset TEXT NOT NULL,
                    last_monthly_reset TEXT NOT NULL,
                    FOREIGN KEY (fcm_token_id) REFERENCES fcm_tokens(id) ON DELETE CASCADE
                );

                INSERT INTO phone_numbers_old (
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
                    id,
                    fcm_token_id,
                    phone_number,
                    daily_limit,
                    monthly_limit,
                    daily_sent_count,
                    monthly_sent_count,
                    last_daily_reset,
                    last_monthly_reset
                FROM phone_numbers;

                DROP TABLE phone_numbers;
                ALTER TABLE phone_numbers_old RENAME TO phone_numbers;

                CREATE UNIQUE INDEX idx_phone_numbers_fcm_token_id_phone_number
                    ON phone_numbers (fcm_token_id, phone_number);

                PRAGMA foreign_keys = ON;
                "#,
            )
            .await?;
        Ok(())
    }
}
