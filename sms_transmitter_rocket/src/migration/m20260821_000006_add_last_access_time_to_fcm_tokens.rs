use sea_orm::ConnectionTrait;
use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20260821_000006_add_last_access_time_to_fcm_tokens"
    }
}

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(FcmTokens::Table)
                    .add_column(ColumnDef::new(FcmTokens::LastAccessTime).date_time())
                    .to_owned(),
            )
            .await?;

        manager
            .get_connection()
            .execute_unprepared(
                r#"
                UPDATE fcm_tokens
                SET last_access_time = CURRENT_TIMESTAMP
                WHERE last_access_time IS NULL;
                "#,
            )
            .await?;

        Ok(())
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(FcmTokens::Table)
                    .drop_column(FcmTokens::LastAccessTime)
                    .to_owned(),
            )
            .await
    }
}

#[derive(Iden)]
enum FcmTokens {
    Table,
    LastAccessTime,
}
