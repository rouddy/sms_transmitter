//! Shared-key JWT authentication.
//!
//! Every caller (API clients and the Android app) holds the same ES256 private key
//! and signs a short-lived JWT per request. The server only holds the public key(s)
//! and checks the signature, so possession of the private key grants access to the
//! whole API. See `jwt_certificate.md` and `script/generate_keys.sh`.

use anyhow::{Context, Result};
use chrono::Utc;
use jsonwebtoken::{
    decode, decode_header, encode, Algorithm, DecodingKey, EncodingKey, Header, Validation,
};
use rocket::http::Status;
use rocket::request::{FromRequest, Outcome, Request};
use serde::{Deserialize, Serialize};
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

#[derive(Debug, Serialize, Deserialize)]
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

        Ok(Self::with_keys(keys))
    }

    fn with_keys(keys: Vec<DecodingKey>) -> Self {
        let mut validation = Validation::new(Algorithm::ES256);
        validation.leeway = CLOCK_SKEW_LEEWAY_SECS as u64;
        validation.validate_aud = false;
        validation.set_required_spec_claims(&["exp", "iat"]);

        Self { keys, validation }
    }

    #[cfg(test)]
    pub fn from_public_pem(pem: &str) -> Self {
        Self::with_keys(vec![DecodingKey::from_ec_pem(pem.as_bytes()).unwrap()])
    }

    /// Checks that `pem` is a private key whose tokens `verify` accepts, by signing one the
    /// same way API callers do. Used by the admin login, which never stores the key.
    pub fn accepts_private_key(&self, pem: &[u8]) -> std::result::Result<(), String> {
        let key =
            EncodingKey::from_ec_pem(pem).map_err(|e| format!("invalid EC private key: {e}"))?;
        let iat = Utc::now().timestamp();
        let claims = Claims { iat, exp: iat + 60 };
        let token = encode(&Header::new(Algorithm::ES256), &claims, &key)
            .map_err(|e| format!("failed to sign test token: {e}"))?;
        self.verify(&token)
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

/// Throwaway P-256 key pairs used only by tests.
#[cfg(test)]
pub mod test_keys {
    pub const PRIVATE_KEY: &str = "-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgBxwVOdzoqJSkEkAQ
+yt1G+5ajmGojPgw9kUKiATDR2ahRANCAASnXNzcxXXKIwuOJExCyWv0fBKhFfzT
sSM+mcA30NWyI8hgLq0sI7x7Aik6q38qTyMxJdQ6669fvSC94UGWMD6l
-----END PRIVATE KEY-----
";
    pub const PUBLIC_KEY: &str = "-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEp1zc3MV1yiMLjiRMQslr9HwSoRX8
07EjPpnAN9DVsiPIYC6tLCO8ewIpOqt/Kk8jMSXUOuuvX70gveFBljA+pQ==
-----END PUBLIC KEY-----
";
    /// Not paired with `PUBLIC_KEY`.
    pub const OTHER_PRIVATE_KEY: &str = "-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgEc3HkDp+yvgjRX5K
TGsw3KqmoYDlZNNvplwE7+VjYSyhRANCAATHZzYiQqDOoo8qSK1X8s+YB1lPTkEx
JHe32gaHHZRc3aHz2IJ32viVJTzl9/tjvO961bgMe4n5oWq9hIiMNegu
-----END PRIVATE KEY-----
";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_only_the_paired_private_key() {
        let verifier = JwtVerifier::from_public_pem(test_keys::PUBLIC_KEY);
        assert!(verifier
            .accepts_private_key(test_keys::PRIVATE_KEY.as_bytes())
            .is_ok());
        assert!(verifier
            .accepts_private_key(test_keys::OTHER_PRIVATE_KEY.as_bytes())
            .is_err());
        assert!(verifier
            .accepts_private_key(test_keys::PUBLIC_KEY.as_bytes())
            .is_err());
    }

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
