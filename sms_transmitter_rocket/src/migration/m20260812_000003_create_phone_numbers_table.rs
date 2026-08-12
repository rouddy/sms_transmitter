use sea_orm_migration::prelude::*;

pub struct Migration;

impl MigrationName for Migration {
    fn name(&self) -> &str {
        "m20260812_000003_create_phone_numbers_table"
    }
}

#[async_trait::async_trait]
impl MigrationTrait for Migration {
    async fn up(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .create_table(
                Table::create()
                    .table(PhoneNumbers::Table)
                    .if_not_exists()
                    .col(
                        ColumnDef::new(PhoneNumbers::Id)
                            .uuid()
                            .not_null()
                            .primary_key(),
                    )
                    .col(ColumnDef::new(PhoneNumbers::FcmTokenId).uuid().not_null())
                    .col(
                        ColumnDef::new(PhoneNumbers::PhoneNumber)
                            .string()
                            .not_null(),
                    )
                    .foreign_key(
                        ForeignKey::create()
                            .name("fk_phone_numbers_fcm_token_id")
                            .from(PhoneNumbers::Table, PhoneNumbers::FcmTokenId)
                            .to(FcmTokens::Table, FcmTokens::Id)
                            .on_delete(ForeignKeyAction::Cascade),
                    )
                    .index(
                        Index::create()
                            .name("idx_phone_numbers_fcm_token_id_phone_number")
                            .table(PhoneNumbers::Table)
                            .col(PhoneNumbers::FcmTokenId)
                            .col(PhoneNumbers::PhoneNumber)
                            .unique(),
                    )
                    .to_owned(),
            )
            .await
    }

    async fn down(&self, manager: &SchemaManager) -> Result<(), DbErr> {
        manager
            .drop_table(Table::drop().table(PhoneNumbers::Table).to_owned())
            .await
    }
}

#[derive(Iden)]
enum PhoneNumbers {
    Table,
    Id,
    FcmTokenId,
    PhoneNumber,
}

#[derive(Iden)]
enum FcmTokens {
    Table,
    Id,
}
