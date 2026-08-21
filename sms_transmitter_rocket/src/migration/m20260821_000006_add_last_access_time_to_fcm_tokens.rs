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
                    .add_column(
                        ColumnDef::new(FcmTokens::LastAccessTime)
                            .date_time()
                            .not_null()
                            .default(Expr::current_timestamp()),
                    )
                    .to_owned(),
            )
            .await
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
