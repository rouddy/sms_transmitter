use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20260716_000002_add_callback_response_to_sms_requests"
    }
}

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(SmsRequests::Table)
                    .add_column(ColumnDef::new(SmsRequests::CallbackResponseCode).integer().null())
                    .add_column(ColumnDef::new(SmsRequests::CallbackResponseBody).text().null())
                    .to_owned(),
            )
            .await
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .alter_table(
                Table::alter()
                    .table(SmsRequests::Table)
                    .drop_column(SmsRequests::CallbackResponseCode)
                    .drop_column(SmsRequests::CallbackResponseBody)
                    .to_owned(),
            )
            .await
    }
}

#[derive(Iden)]
enum SmsRequests {
    Table,
    CallbackResponseCode,
    CallbackResponseBody,
}
