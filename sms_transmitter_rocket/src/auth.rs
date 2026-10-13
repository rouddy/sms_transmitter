//! Shared-key JWT authentication.
//!
//! Every caller (API clients and the Android app) holds the same ES256 private key
//! and signs a short-lived JWT per request. The server only holds the public key(s)
//! and checks the signature, so possession of the private key grants access to the
//! whole API. See `jwt_certificate.md` and `script/generate_keys.sh`.

use anyhow::{Context, Result};
use chrono::Utc;
use jsonwebtoken::{decode, decode_header, Algorithm, DecodingKey, Validation};
use rocket::http::Status;
use rocket::request::{FromRequest, Outcome, Request};
use serde::Deserialize;
use std::path::Path;

pub const PUBLIC_KEY_FILE: &str = "public_key.pem";
/// Kept by `generate_keys.sh --rotate` so callers still using the old private key
/// keep working until they are updated. Delete it to finish the rotation.
pub const PREVIOUS_PUBLIC_KEY_FILE: &str = "public_key.previous.pem";

/// Longest lifetime (`exp - iat`) a caller may give a token.
const MAX_TOKEN_LIFETIME_SECS: i64 = 300;
/// Allowed clock difference between the caller and the server.
const CLOCK_SKEW_LEEWAY_SECS: i64 = 60;
const MAX_TOKEN_LEN: usize = 4096;

pub struct JwtVerifier {
    keys: Vec<DecodingKey>,
    validation: Validation,
}

#[derive(Debug, Deserialize)]
struct Claims {
    iat: i64,
    exp: i64,
}

impl JwtVerifier {
    /// Loads `public_key.pem` (required) and `public_key.previous.pem` (optional)
    /// from `keys_dir`.
    pub fn from_dir(keys_dir: &str) -> Result<Self> {
        let dir = Path::new(keys_dir);
        let current = dir.join(PUBLIC_KEY_FILE);
        let pem = std::fs::read(&current).with_context(|| {
            format!(
                "Auth public key not found at {}. Run script/generate_keys.sh to create a key pair",
                current.display()
            )
        })?;
        let mut keys = vec![DecodingKey::from_ec_pem(&pem)
            .with_context(|| format!("Invalid EC public key at {}", current.display()))?];

        let previous = dir.join(PREVIOUS_PUBLIC_KEY_FILE);
        if previous.exists() {
            let pem = std::fs::read(&previous)
                .with_context(|| format!("Failed to read {}", previous.display()))?;
            keys.push(
                DecodingKey::from_ec_pem(&pem)
                    .with_context(|| format!("Invalid EC public key at {}", previous.display()))?,
            );
            tracing::warn!(
                "Accepting tokens signed with the previous key ({}). Delete it once all callers use the new key",
                previous.display()
            );
        }

        let mut validation = Validation::new(Algorithm::ES256);
        validation.leeway = CLOCK_SKEW_LEEWAY_SECS as u64;
        validation.validate_aud = false;
        validation.set_required_spec_claims(&["exp", "iat"]);

        Ok(Self { keys, validation })
    }

    pub fn verify(&self, token: &str) -> std::result::Result<(), String> {
        if token.len() > MAX_TOKEN_LEN {
            return Err("token too long".into());
        }
        let header = decode_header(token).map_err(|e| format!("malformed header: {e}"))?;
        if header.alg != Algorithm::ES256 {
            return Err(format!("unsupported alg {:?}", header.alg));
        }

        let mut last_error = String::from("no key");
        for key in &self.keys {
            match decode::<Claims>(token, key, &self.validation) {
                Ok(data) => return check_lifetime(&data.claims, Utc::now().timestamp()),
                Err(e) => last_error = e.to_string(),
            }
        }
        Err(last_error)
    }
}

fn check_lifetime(claims: &Claims, now: i64) -> std::result::Result<(), String> {
    if claims.iat > now + CLOCK_SKEW_LEEWAY_SECS {
        return Err("iat is in the future".into());
    }
    if claims.exp - claims.iat > MAX_TOKEN_LIFETIME_SECS {
        return Err(format!("token lifetime exceeds {MAX_TOKEN_LIFETIME_SECS}s"));
    }
    Ok(())
}

/// Request guard: succeeds only with a valid `Authorization: Bearer <JWT>` header.
pub struct Authenticated;

#[rocket::async_trait]
impl<'r> FromRequest<'r> for Authenticated {
    type Error = ();

    async fn from_request(request: &'r Request<'_>) -> Outcome<Self, Self::Error> {
        let Some(verifier) = request.rocket().state::<JwtVerifier>() else {
            tracing::error!("JwtVerifier is not managed by Rocket");
            return Outcome::Error((Status::InternalServerError, ()));
        };
        let Some(token) = request
            .headers()
            .get_one("Authorization")
            .and_then(|value| value.strip_prefix("Bearer "))
        else {
            return Outcome::Error((Status::Unauthorized, ()));
        };

        match verifier.verify(token.trim()) {
            Ok(()) => Outcome::Success(Authenticated),
            Err(reason) => {
                tracing::warn!("Rejected request from {:?}: {reason}", request.client_ip());
                Outcome::Error((Status::Unauthorized, ()))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_token_within_lifetime() {
        assert!(check_lifetime(
            &Claims {
                iat: 1000,
                exp: 1300
            },
            1000
        )
        .is_ok());
    }

    #[test]
    fn rejects_long_lived_token() {
        assert!(check_lifetime(
            &Claims {
                iat: 1000,
                exp: 1301
            },
            1000
        )
        .is_err());
    }

    #[test]
    fn rejects_token_issued_in_future() {
        assert!(check_lifetime(
            &Claims {
                iat: 1061,
                exp: 1100
            },
            1000
        )
        .is_err());
    }
}
