#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# generate_keys.sh
# Creates (or rotates) the shared ES256 key pair used for API authentication.
#
#   keys/private_key.pem           PKCS#8 private key — give to API callers and
#                                  embed in the Android APK. Never commit it.
#   keys/public_key.pem            Public key — the server verifies JWTs with it.
#   keys/public_key.previous.pem   (after --rotate) old public key, still accepted
#                                  by the server until you delete it.
#
# Usage (run from any directory):
#   ./script/generate_keys.sh              # create a key pair if none exists
#   ./script/generate_keys.sh --rotate     # replace the existing key pair
#   KEYS_DIR=/path/to/keys ./script/generate_keys.sh
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
KEYS_DIR="${KEYS_DIR:-${REPO_ROOT}/keys}"

PRIVATE_KEY="${KEYS_DIR}/private_key.pem"
PUBLIC_KEY="${KEYS_DIR}/public_key.pem"
PREVIOUS_PUBLIC_KEY="${KEYS_DIR}/public_key.previous.pem"

ROTATE=false
case "${1:-}" in
  "") ;;
  --rotate) ROTATE=true ;;
  -h|--help)
    sed -n '4,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "ERROR: unknown option: $1 (use --rotate or --help)" >&2
    exit 1
    ;;
esac

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl is required" >&2
  exit 1
fi

mkdir -p "${KEYS_DIR}"
chmod 700 "${KEYS_DIR}"

if [[ -f "${PRIVATE_KEY}" || -f "${PUBLIC_KEY}" ]]; then
  if [[ "${ROTATE}" != true ]]; then
    echo "Key pair already exists in ${KEYS_DIR}. Use --rotate to replace it."
    exit 0
  fi
  if [[ ! -f "${PUBLIC_KEY}" ]]; then
    echo "ERROR: ${PUBLIC_KEY} is missing; cannot keep the old public key for rotation." >&2
    exit 1
  fi
  BACKUP_DIR="${KEYS_DIR}/backup/$(date +%Y%m%d%H%M%S)"
  mkdir -p "${BACKUP_DIR}"
  cp -p "${KEYS_DIR}"/*.pem "${BACKUP_DIR}/"
  chmod 600 "${BACKUP_DIR}"/*.pem
  cp -p "${PUBLIC_KEY}" "${PREVIOUS_PUBLIC_KEY}"
  echo "==> Backed up the old key pair to ${BACKUP_DIR}"
fi

# Write to temp files first so a failure never leaves a half-written pair.
umask 077
TMP_PRIVATE="$(mktemp "${KEYS_DIR}/.private_key.XXXXXX")"
TMP_PUBLIC="$(mktemp "${KEYS_DIR}/.public_key.XXXXXX")"
trap 'rm -f "${TMP_PRIVATE}" "${TMP_PUBLIC}"' EXIT

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "${TMP_PRIVATE}" 2>/dev/null
openssl pkey -in "${TMP_PRIVATE}" -pubout -out "${TMP_PUBLIC}" 2>/dev/null

mv "${TMP_PRIVATE}" "${PRIVATE_KEY}"
mv "${TMP_PUBLIC}" "${PUBLIC_KEY}"
chmod 600 "${PRIVATE_KEY}"
chmod 644 "${PUBLIC_KEY}"

echo "==> Generated ES256 key pair in ${KEYS_DIR}"
echo "    private: ${PRIVATE_KEY}"
echo "    public:  ${PUBLIC_KEY}"

if [[ "${ROTATE}" == true ]]; then
  cat <<EOF

Next steps:
  1. Restart the server. It accepts both the new key and the old one
     (${PREVIOUS_PUBLIC_KEY}).
  2. Rebuild the Android APK and update every API caller with the new private key.
  3. Delete ${PREVIOUS_PUBLIC_KEY} and restart the server to stop accepting the old key.
EOF
fi
