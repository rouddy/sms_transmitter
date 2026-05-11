use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20240101_000001_create_tables"
    }
}

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .create_table(
                Table::create()
                    .table(FcmTokens::Table)
                    .if_not_exists()
                    .col(ColumnDef::new(FcmTokens::Id).uuid().not_null().primary_key())
                    .col(
                        ColumnDef::new(FcmTokens::Token)
                            .string()
                            .not_null()
                            .unique_key(),
                    )
                    .col(ColumnDef::new(FcmTokens::DeviceInfo).string().not_null())
                    .col(ColumnDef::new(FcmTokens::DailyLimit).integer().not_null())
                    .col(ColumnDef::new(FcmTokens::MonthlyLimit).integer().not_null())
                    .col(
                        ColumnDef::new(FcmTokens::DailySentCount)
                            .integer()
                            .not_null()
                            .default(0),
                    )
                    .col(
                        ColumnDef::new(FcmTokens::MonthlySentCount)
                            .integer()
                            .not_null()
                            .default(0),
                    )
                    .col(
                        ColumnDef::new(FcmTokens::LastDailyReset)
                            .date_time()
                            .not_null(),
                    )
                    .col(
                        ColumnDef::new(FcmTokens::LastMonthlyReset)
                            .date_time()
                            .not_null(),
                    )
                    .col(ColumnDef::new(FcmTokens::CreatedAt).date_time().not_null())
                    .col(ColumnDef::new(FcmTokens::UpdatedAt).date_time().not_null())
                    .to_owned(),
            )
            .await?;

        manager
            .create_table(
                Table::create()
                    .table(SmsRequests::Table)
                    .if_not_exists()
                    .col(
                        ColumnDef::new(SmsRequests::Id)
                            .uuid()
                            .not_null()
                            .primary_key(),
                    )
                    .col(
                        ColumnDef::new(SmsRequests::PhoneNumber)
                            .string()
                            .not_null(),
                    )
                    .col(ColumnDef::new(SmsRequests::Message).text().not_null())
                    .col(ColumnDef::new(SmsRequests::CallbackUrl).string().null())
                    .col(ColumnDef::new(SmsRequests::Status).string().not_null())
                    .col(ColumnDef::new(SmsRequests::AssignedTokenId).uuid().null())
                    .col(ColumnDef::new(SmsRequests::Error).text().null())
                    .col(
                        ColumnDef::new(SmsRequests::CreatedAt)
                            .date_time()
                            .not_null(),
                    )
                    .col(
                        ColumnDef::new(SmsRequests::UpdatedAt)
                            .date_time()
                            .not_null(),
                    )
                    .foreign_key(
                        ForeignKey::create()
                            .name("fk_sms_requests_assigned_token_id")
                            .from(SmsRequests::Table, SmsRequests::AssignedTokenId)
                            .to(FcmTokens::Table, FcmTokens::Id)
                            .on_delete(ForeignKeyAction::SetNull),
                    )
                    .to_owned(),
            )
            .await?;

        Ok(())
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .drop_table(Table::drop().table(SmsRequests::Table).to_owned())
            .await?;
        manager
            .drop_table(Table::drop().table(FcmTokens::Table).to_owned())
            .await?;
        Ok(())
    }
}

#[derive(Iden)]
enum FcmTokens {
    Table,
    Id,
    Token,
    DeviceInfo,
    DailyLimit,
    MonthlyLimit,
    DailySentCount,
    MonthlySentCount,
    LastDailyReset,
    LastMonthlyReset,
    CreatedAt,
    UpdatedAt,
}

#[derive(Iden)]
enum SmsRequests {
    Table,
    Id,
    PhoneNumber,
    Message,
    CallbackUrl,
    Status,
    AssignedTokenId,
    Error,
    CreatedAt,
    UpdatedAt,
}
