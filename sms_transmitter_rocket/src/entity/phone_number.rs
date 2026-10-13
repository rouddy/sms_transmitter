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

/// Sent counts tallied by the device from its SMS history: the day count for `counted_on`
/// (device-local date) and the month count for the month containing it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ReportedSentCounts {
    pub counted_on: NaiveDate,
    pub daily: i32,
    pub monthly: i32,
}

const SENT_COUNT_COLUMNS: [Column; 4] = [
    Column::DailySentCount,
    Column::MonthlySentCount,
    Column::LastDailyReset,
    Column::LastMonthlyReset,
];

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

    /// Merges counts reported by the device, whose SMS history is the source of truth.
    ///
    /// Reports can arrive out of order, and a reinstalled app reports from an empty history,
    /// so counts never decrease within a period. Counts for a period other than the server's
    /// current one (device clock or time zone differs around midnight) are ignored; the next
    /// report or registration catches up.
    pub fn merge_reported_sent_counts(&mut self, reported: ReportedSentCounts, today: NaiveDate) {
        if reported.counted_on == today {
            self.daily_sent_count = self.effective_daily_sent_count(today).max(reported.daily);
            self.last_daily_reset = today;
        }
        if (reported.counted_on.year(), reported.counted_on.month())
            == (today.year(), today.month())
        {
            self.monthly_sent_count = self
                .effective_monthly_sent_count(today)
                .max(reported.monthly);
            self.last_monthly_reset = today;
        }
    }

    /// Converts to an `ActiveModel` that persists the sent-count columns.
    ///
    /// `Model::into()` marks every column `Unchanged`, so updating that `ActiveModel` after
    /// changing counts in memory would write nothing.
    pub fn into_sent_counts_active_model(self) -> ActiveModel {
        let mut active: ActiveModel = self.into();
        for column in SENT_COUNT_COLUMNS {
            active.reset(column);
        }
        active
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
    use super::{Entity, Model, ReportedSentCounts};
    use chrono::NaiveDate;
    use sea_orm::{
        ActiveModelTrait, ConnectionTrait, Database, DatabaseConnection, EntityTrait, Schema,
    };
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

    fn reported(counted_on: NaiveDate, daily: i32, monthly: i32) -> ReportedSentCounts {
        ReportedSentCounts {
            counted_on,
            daily,
            monthly,
        }
    }

    #[test]
    fn merge_takes_reported_counts_when_higher() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 2, 20, today, date(2026, 8, 1));
        m.merge_reported_sent_counts(reported(today, 5, 30), today);
        assert_eq!((m.daily_sent_count, m.monthly_sent_count), (5, 30));
        assert_eq!((m.last_daily_reset, m.last_monthly_reset), (today, today));
    }

    #[test]
    fn merge_never_decreases_counts_within_period() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 5, 30, today, today);
        m.merge_reported_sent_counts(reported(today, 0, 0), today);
        assert_eq!((m.daily_sent_count, m.monthly_sent_count), (5, 30));
    }

    #[test]
    fn merge_replaces_counts_from_a_past_period() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 9, 90, date(2026, 8, 26), date(2026, 7, 31));
        m.merge_reported_sent_counts(reported(today, 1, 4), today);
        assert_eq!((m.daily_sent_count, m.monthly_sent_count), (1, 4));
        assert_eq!((m.last_daily_reset, m.last_monthly_reset), (today, today));
    }

    #[test]
    fn merge_applies_only_monthly_when_reported_day_differs_in_same_month() {
        let today = date(2026, 8, 27);
        let mut m = model(10, 100, 3, 30, today, today);
        m.merge_reported_sent_counts(reported(date(2026, 8, 26), 8, 40), today);
        assert_eq!((m.daily_sent_count, m.monthly_sent_count), (3, 40));
    }

    #[test]
    fn merge_ignores_counts_from_another_month() {
        let today = date(2026, 9, 1);
        let mut m = model(10, 100, 0, 0, today, today);
        m.merge_reported_sent_counts(reported(date(2026, 8, 31), 8, 80), today);
        assert_eq!((m.daily_sent_count, m.monthly_sent_count), (0, 0));
    }

    async fn db_with_row(row: &Model) -> DatabaseConnection {
        let db = Database::connect("sqlite::memory:").await.unwrap();
        // Only phone_numbers is created; skip the fcm_tokens foreign key.
        db.execute_unprepared("PRAGMA foreign_keys = OFF")
            .await
            .unwrap();
        let backend = db.get_database_backend();
        let stmt = Schema::new(backend).create_table_from_entity(Entity);
        db.execute(backend.build(&stmt)).await.unwrap();
        let active: super::ActiveModel = row.clone().into();
        Entity::insert(active.reset_all()).exec(&db).await.unwrap();
        db
    }

    #[tokio::test]
    async fn plain_into_does_not_persist_count_changes() {
        let today = date(2026, 8, 27);
        let original = model(10, 100, 0, 0, today, today);
        let db = db_with_row(&original).await;

        let mut changed = original.clone();
        changed.increment_sent_counts(today);
        let active: super::ActiveModel = changed.into();
        active.update(&db).await.unwrap();

        let stored = Entity::find_by_id(original.id)
            .one(&db)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(stored.daily_sent_count, 0);
    }

    #[tokio::test]
    async fn sent_counts_active_model_persists_count_changes() {
        let today = date(2026, 8, 27);
        let original = model(10, 100, 0, 0, date(2026, 8, 26), date(2026, 7, 31));
        let db = db_with_row(&original).await;

        let mut changed = original.clone();
        changed.increment_sent_counts(today);
        changed
            .into_sent_counts_active_model()
            .update(&db)
            .await
            .unwrap();

        let stored = Entity::find_by_id(original.id)
            .one(&db)
            .await
            .unwrap()
            .unwrap();
        assert_eq!((stored.daily_sent_count, stored.monthly_sent_count), (1, 1));
        assert_eq!(
            (stored.last_daily_reset, stored.last_monthly_reset),
            (today, today)
        );
    }
}
