//! Admin web sessions.
//!
//! The operator proves possession of the shared private key once at login
//! (`JwtVerifier::accepts_private_key`); the key is then discarded and the browser only
//! holds an opaque session cookie. Open admin pages send a heartbeat, so a session lives
//! while any admin page is open and expires `IDLE_TIMEOUT` after the last one closes. The
//! cookie has no expiry, so quitting the browser also ends it.

use rand::rngs::OsRng;
use rand::RngCore;
use rocket::http::{Cookie, SameSite, Status};
use rocket::request::{FromRequest, Outcome, Request};
use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

pub const SESSION_COOKIE: &str = "admin_session";
const COOKIE_PATH: &str = "/admin";

/// How often open pages call `/admin/heartbeat`. Browsers throttle timers in background
/// tabs to about once a minute, so this must stay well under `IDLE_TIMEOUT`.
pub const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(60);
/// A session expires once no page or heartbeat has used it for this long.
const IDLE_TIMEOUT: Duration = Duration::from_secs(5 * 60);

#[derive(Default)]
pub struct AdminSessions {
    sessions: Mutex<HashMap<String, Session>>,
}

struct Session {
    csrf_token: String,
    last_seen: Instant,
}

impl Session {
    fn is_expired(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.last_seen) > IDLE_TIMEOUT
    }
}

impl AdminSessions {
    /// Starts a session and returns its ID.
    pub fn create(&self) -> String {
        self.create_at(Instant::now())
    }

    fn create_at(&self, now: Instant) -> String {
        let id = random_token();
        let mut sessions = self.lock();
        sessions.retain(|_, session| !session.is_expired(now));
        sessions.insert(
            id.clone(),
            Session {
                csrf_token: random_token(),
                last_seen: now,
            },
        );
        id
    }

    /// Extends the session and returns its CSRF token, or `None` if it is unknown or expired.
    fn touch_at(&self, id: &str, now: Instant) -> Option<String> {
        let mut sessions = self.lock();
        let session = sessions.get_mut(id)?;
        if session.is_expired(now) {
            sessions.remove(id);
            return None;
        }
        session.last_seen = now;
        Some(session.csrf_token.clone())
    }

    pub fn remove(&self, id: &str) {
        self.lock().remove(id);
    }

    fn lock(&self) -> MutexGuard<'_, HashMap<String, Session>> {
        self.sessions.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// No max-age/expires: the browser drops it when it quits.
pub fn session_cookie(id: String) -> Cookie<'static> {
    Cookie::build((SESSION_COOKIE, id))
        .path(COOKIE_PATH)
        .http_only(true)
        .same_site(SameSite::Strict)
        .build()
}

pub fn removal_cookie() -> Cookie<'static> {
    Cookie::build(SESSION_COOKIE).path(COOKIE_PATH).build()
}

/// Request guard for admin pages. Fails with 401, which the `/admin` catcher turns into a
/// redirect to the login page.
pub struct AdminSession {
    pub id: String,
    pub csrf_token: String,
}

impl AdminSession {
    /// Rejects a form post whose CSRF token is not this session's.
    pub fn check_csrf(&self, token: &str) -> Result<(), Status> {
        if token == self.csrf_token {
            Ok(())
        } else {
            Err(Status::Forbidden)
        }
    }
}

#[rocket::async_trait]
impl<'r> FromRequest<'r> for AdminSession {
    type Error = ();

    async fn from_request(request: &'r Request<'_>) -> Outcome<Self, Self::Error> {
        let Some(sessions) = request.rocket().state::<AdminSessions>() else {
            tracing::error!("AdminSessions is not managed by Rocket");
            return Outcome::Error((Status::InternalServerError, ()));
        };
        let Some(id) = request
            .cookies()
            .get(SESSION_COOKIE)
            .map(|cookie| cookie.value().to_string())
        else {
            return Outcome::Error((Status::Unauthorized, ()));
        };

        match sessions.touch_at(&id, Instant::now()) {
            Some(csrf_token) => Outcome::Success(AdminSession { id, csrf_token }),
            None => Outcome::Error((Status::Unauthorized, ())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_lives_while_used_and_expires_when_idle() {
        let sessions = AdminSessions::default();
        let start = Instant::now();
        let id = sessions.create_at(start);

        let csrf = sessions.touch_at(&id, start + Duration::from_secs(4 * 60));
        assert!(csrf.is_some());
        assert_eq!(
            sessions.touch_at(&id, start + Duration::from_secs(8 * 60)),
            csrf
        );

        assert_eq!(
            sessions.touch_at(&id, start + Duration::from_secs(14 * 60)),
            None
        );
        assert_eq!(sessions.touch_at(&id, start), None);
    }

    #[test]
    fn removed_or_unknown_sessions_are_rejected() {
        let sessions = AdminSessions::default();
        let id = sessions.create();
        sessions.remove(&id);
        assert_eq!(sessions.touch_at(&id, Instant::now()), None);
        assert_eq!(sessions.touch_at("unknown", Instant::now()), None);
    }
}
