use anyhow::Result;
use sea_orm::{Database, DatabaseConnection};

pub async fn connect(database_url: &str) -> Result<DatabaseConnection> {
    let db = Database::connect(database_url).await?;
    Ok(db)
}
