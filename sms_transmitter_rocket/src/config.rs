use std::env;

#[derive(Debug, Clone)]
pub struct AppConfig {
    pub database_url: String,
    pub firebase_credentials_path: String,
    pub firebase_project_id: String,
}

impl AppConfig {
    pub fn from_env() -> anyhow::Result<Self> {
        Ok(Self {
            database_url: env::var("DATABASE_URL")
                .unwrap_or_else(|_| "sqlite://sms_transmitter.db?mode=rwc".to_string()),
            firebase_credentials_path: env::var("FIREBASE_CREDENTIALS_PATH")
                .unwrap_or_else(|_| "firebase-credentials.json".to_string()),
            firebase_project_id: env::var("FIREBASE_PROJECT_ID")?,
        })
    }
}
