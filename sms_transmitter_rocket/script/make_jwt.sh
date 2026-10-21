#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# make_jwt.sh
# Prints a short-lived ES256 JWT signed with keys/private_key.pem.
# Use it for curl or the GraphiQL "Headers" tab.
#
# Usage:
#   ./script/make_jwt.sh                         # valid for 300 seconds
#   PRIVATE_KEY=/path/to/private_key.pem TTL=60 ./script/make_jwt.sh
#
#   curl -H "Authorization: Bearer $(./script/make_jwt.sh)" \
#        -H 'Content-Type: application/json' \
#        -d '{"query":"{ phoneNumbers { phoneNumber } }"}' \
#        http://localhost:54323/graphql
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PRIVATE_KEY="${PRIVATE_KEY:-${REPO_ROOT}/keys/private_key.pem}"
TTL="${TTL:-300}"

if [[ ! -f "${PRIVATE_KEY}" ]]; then
  echo "ERROR: ${PRIVATE_KEY} not found. Run script/generate_keys.sh first." >&2
  exit 1
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

NOW="$(date +%s)"
JTI="$(openssl rand -hex 16)"
HEADER="$(printf '{"alg":"ES256","typ":"JWT"}' | b64url)"
PAYLOAD="$(printf '{"iat":%d,"exp":%d,"jti":"%s"}' "${NOW}" "$((NOW + TTL))" "${JTI}" | b64url)"
SIGNING_INPUT="${HEADER}.${PAYLOAD}"

# openssl emits a DER-encoded ECDSA signature; JWS ES256 needs raw r||s (32 bytes each).
SIGNATURE="$(
  printf '%s' "${SIGNING_INPUT}" \
    | openssl dgst -sha256 -sign "${PRIVATE_KEY}" \
    | openssl asn1parse -inform DER \
    | awk -F: '/INTEGER/ { printf "%064s", $NF }' \
    | tr ' ' '0' \
    | xxd -r -p \
    | b64url
)"

printf '%s.%s\n' "${SIGNING_INPUT}" "${SIGNATURE}"
