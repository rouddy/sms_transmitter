use chrono::{Datelike, Local, NaiveDate};
use sea_orm::entity::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, DeriveEntityModel, Serialize, Deserialize)]
#[sea_orm(table_name = "phone_numbers")]
pub struct Model {
    #[sea_orm(primary_key, auto_increment = false)]
    pub id: Uuid,
    pub fcm_token_id: Uuid,
    pub phone_number: String,
    pub daily_limit: i32,
    pub monthly_limit: i32,
    pub daily_sent_count: i32,
    pub monthly_sent_count: i32,
    pub last_daily_reset: NaiveDate,
    pub last_monthly_reset: NaiveDate,
}

#[derive(Copy, Clone, Debug, EnumIter, DeriveRelation)]
pub enum Relation {
    #[sea_orm(
        belongs_to = "super::fcm_token::Entity",
        from = "Column::FcmTokenId",
        to = "super::fcm_token::Column::Id",
        on_delete = "Cascade"
    )]
    FcmToken,
}

impl Related<super::fcm_token::Entity> for Entity {
    fn to() -> RelationDef {
        Relation::FcmToken.def()
    }
}

impl ActiveModelBehavior for ActiveModel {}

impl Model {
    fn effective_daily_sent_count(&self, today: NaiveDate) -> i32 {
        if self.last_daily_reset < today {
            0
        } else {
            self.daily_sent_count
        }
    }

    fn effective_monthly_sent_count(&self, today: NaiveDate) -> i32 {
        if (
            self.last_monthly_reset.year(),
            self.last_monthly_reset.month(),
        ) < (today.year(), today.month())
        {
            0
        } else {
            self.monthly_sent_count
        }
    }

    pub fn daily_remaining(&self) -> Option<i32> {
        if self.daily_limit == 0 {
            return None;
        }
        let today = Local::now().date_naive();
        Some((self.daily_limit - self.effective_daily_sent_count(today)).max(0))
    }

    pub fn monthly_remaining(&self) -> Option<i32> {
        if self.monthly_limit == 0 {
            return None;
        }
        let today = Local::now().date_naive();
        Some((self.monthly_limit - self.effective_monthly_sent_count(today)).max(0))
    }

    pub fn can_send_now(&self) -> bool {
        self.daily_remaining()
            .map(|remaining| remaining > 0)
            .unwrap_or(true)
            && self
                .monthly_remaining()
                .map(|remaining| remaining > 0)
                .unwrap_or(true)
    }

    pub fn availability_score(&self) -> f32 {
        let daily_ratio = match self.daily_remaining() {
            Some(remaining) => remaining as f32 / self.daily_limit as f32,
            None => 1.0,
        };
        let monthly_ratio = match self.monthly_remaining() {
            Some(remaining) => remaining as f32 / self.monthly_limit as f32,
            None => 1.0,
        };
        daily_ratio.min(monthly_ratio)
    }

    pub fn increment_sent_counts(&mut self, today: NaiveDate) {
        if self.last_daily_reset < today {
            self.daily_sent_count = 0;
        }
        if (
            self.last_monthly_reset.year(),
            self.last_monthly_reset.month(),
        ) < (today.year(), today.month())
        {
            self.monthly_sent_count = 0;
        }
        self.daily_sent_count += 1;
        self.monthly_sent_count += 1;
        self.last_daily_reset = today;
        self.last_monthly_reset = today;
    }
}
