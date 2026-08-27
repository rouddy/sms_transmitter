use chrono::{Datelike, NaiveDate};
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
    pub fn effective_daily_sent_count(&self, today: NaiveDate) -> i32 {
        if self.last_daily_reset < today {
            0
        } else {
            self.daily_sent_count
        }
    }

    pub fn effective_monthly_sent_count(&self, today: NaiveDate) -> i32 {
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

    pub fn daily_remaining(&self, today: NaiveDate) -> Option<i32> {
        if self.daily_limit == 0 {
            return None;
        }
        Some((self.daily_limit - self.effective_daily_sent_count(today)).max(0))
    }

    pub fn monthly_remaining(&self, today: NaiveDate) -> Option<i32> {
        if self.monthly_limit == 0 {
            return None;
        }
        Some((self.monthly_limit - self.effective_monthly_sent_count(today)).max(0))
    }

    pub fn can_send_now(&self, today: NaiveDate) -> bool {
        self.daily_remaining(today)
            .map(|remaining| remaining > 0)
            .unwrap_or(true)
            && self
                .monthly_remaining(today)
                .map(|remaining| remaining > 0)
                .unwrap_or(true)
    }

    pub fn availability_score(&self, today: NaiveDate) -> f32 {
        let daily_ratio = match self.daily_remaining(today) {
            Some(remaining) => remaining as f32 / self.daily_limit as f32,
            None => 0.0,
        };
        let monthly_ratio = match self.monthly_remaining(today) {
            Some(remaining) => remaining as f32 / self.monthly_limit as f32,
            None => 0.0,
        };
        daily_ratio * 10.0 + monthly_ratio
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

#[cfg(test)]
mod tests {
    use super::Model;
    use chrono::NaiveDate;
    use uuid::Uuid;

    fn date(year: i32, month: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(year, month, day).unwrap()
    }

    fn model(
        daily_limit: i32,
        monthly_limit: i32,
        daily_sent_count: i32,
        monthly_sent_count: i32,
        last_daily_reset: NaiveDate,
        last_monthly_reset: NaiveDate,
    ) -> Model {
        Model {
            id: Uuid::new_v4(),
            fcm_token_id: Uuid::new_v4(),
            phone_number: "01012345678".to_string(),
            daily_limit,
            monthly_limit,
            daily_sent_count,
            monthly_sent_count,
            last_daily_reset,
            last_monthly_reset,
        }
    }

    #[test]
    fn effective_daily_sent_count_is_zero_when_reset_is_before_today() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 5, 20, date(2026, 8, 26), date(2026, 8, 1));
        assert_eq!(m.effective_daily_sent_count(today), 0);
    }

    #[test]
    fn effective_daily_sent_count_matches_when_reset_is_today() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 5, 20, date(2026, 8, 27), date(2026, 8, 1));
        assert_eq!(m.effective_daily_sent_count(today), 5);
    }

    #[test]
    fn effective_monthly_sent_count_is_zero_for_previous_month() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 5, 70, date(2026, 8, 27), date(2026, 7, 15));
        assert_eq!(m.effective_monthly_sent_count(today), 0);
    }

    #[test]
    fn effective_monthly_sent_count_matches_for_same_month() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 5, 70, date(2026, 8, 27), date(2026, 8, 1));
        assert_eq!(m.effective_monthly_sent_count(today), 70);
    }

    #[test]
    fn daily_remaining_is_none_when_unlimited() {
        let today = date(2026, 8, 27);
        let m = model(0, 100, 0, 0, today, today);
        assert_eq!(m.daily_remaining(today), None);
    }

    #[test]
    fn daily_remaining_reflects_full_limit_after_reset() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 5, 20, date(2026, 8, 26), date(2026, 8, 1));
        assert_eq!(m.daily_remaining(today), Some(10));
    }

    #[test]
    fn daily_remaining_is_clamped_at_zero() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 15, 70, today, today);
        assert_eq!(m.daily_remaining(today), Some(0));
    }

    #[test]
    fn monthly_remaining_is_none_when_unlimited() {
        let today = date(2026, 8, 27);
        let m = model(10, 0, 0, 0, today, today);
        assert_eq!(m.monthly_remaining(today), None);
    }

    #[test]
    fn monthly_remaining_subtracts_effective_count() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 0, 40, today, date(2026, 8, 1));
        assert_eq!(m.monthly_remaining(today), Some(60));
    }

    #[test]
    fn can_send_now_is_true_when_unlimited() {
        let today = date(2026, 8, 27);
        let m = model(0, 0, 0, 0, today, today);
        assert!(m.can_send_now(today));
    }

    #[test]
    fn can_send_now_is_false_when_daily_limit_reached() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 10, 0, today, date(2026, 8, 1));
        assert!(!m.can_send_now(today));
    }

    #[test]
    fn can_send_now_is_false_when_monthly_limit_reached() {
        let today = date(2026, 8, 27);
        let m = model(100, 10, 0, 10, today, date(2026, 8, 1));
        assert!(!m.can_send_now(today));
    }

    #[test]
    fn can_send_now_is_true_when_under_limits() {
        let today = date(2026, 8, 27);
        let m = model(10, 100, 3, 50, today, date(2026, 8, 1));
        assert!(m.can_send_now(today));
    }

    #[test]
    fn availability_score_is_zero_when_unlimited() {
        let today = date(2026, 8, 27);
        let m = model(0, 0, 0, 0, today, today);
        assert_eq!(m.availability_score(today), 0.0);
    }

    #[test]
    fn availability_score_prefers_more_remaining() {
        let today = date(2026, 8, 27);
        let more = model(10, 100, 0, 0, today, date(2026, 8, 1));
        let less = model(10, 100, 9, 0, today, date(2026, 8, 1));
        assert!(more.availability_score(today) > less.availability_score(today));
    }

    #[test]
    fn increment_sent_counts_resets_daily_on_new_day() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 5, 20, date(2026, 8, 26), date(2026, 8, 1));
        m.increment_sent_counts(today);
        assert_eq!(m.daily_sent_count, 1);
        assert_eq!(m.last_daily_reset, today);
    }

    #[test]
    fn increment_sent_counts_resets_monthly_on_new_month() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 5, 70, today, date(2026, 7, 15));
        m.increment_sent_counts(today);
        assert_eq!(m.monthly_sent_count, 1);
        assert_eq!(m.last_monthly_reset, today);
    }

    #[test]
    fn increment_sent_counts_accumulates_within_same_period() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 5, 70, today, date(2026, 8, 1));
        m.increment_sent_counts(today);
        assert_eq!(m.daily_sent_count, 6);
        assert_eq!(m.monthly_sent_count, 71);
    }
}
