use std::env;

#[derive(Debug, Clone)]
pub struct AppConfig {
    pub database_url: String,
    pub firebase_credentials_path: String,
}

impl AppConfig {
    pub fn from_env() -> anyhow::Result<Self> {
        Ok(Self {
            database_url: env::var("DATABASE_URL")
                .unwrap_or_else(|_| "sqlite://db/sms_transmitter.db?mode=rwc".to_string()),
            firebase_credentials_path: env::var("FIREBASE_CREDENTIALS_PATH")
                .unwrap_or_else(|_| "firebase-credentials.json".to_string()),
        })
    }
}
