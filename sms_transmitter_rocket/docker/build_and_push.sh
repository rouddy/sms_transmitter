#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# build_and_push.sh
# Reads the crate version from Cargo.toml, builds the Docker image,
# and pushes it to Docker Hub.
#
# Usage (run from any directory):
#   ./docker/build_and_push.sh
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "SCRIPT_DIR: ${SCRIPT_DIR}"
echo "REPO_ROOT: ${REPO_ROOT}"

IMAGE_NAME="rouddy/sms_transmitter"
CARGO_TOML="${REPO_ROOT}/Cargo.toml"
DOCKERFILE="${SCRIPT_DIR}/Dockerfile"

# Extract version from Cargo.toml (matches: version = "x.y.z")
VERSION="$(grep -m1 '^version' "${CARGO_TOML}" | sed 's/.*"\(.*\)".*/\1/')"

if [[ -z "${VERSION}" ]]; then
  echo "ERROR: Could not extract version from ${CARGO_TOML}" >&2
  exit 1
fi

FULL_IMAGE="${IMAGE_NAME}:${VERSION}"
LATEST_IMAGE="${IMAGE_NAME}:latest"

echo "==> Building and pushing ${FULL_IMAGE} for linux/amd64,linux/arm64"
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --file "${DOCKERFILE}" \
  --tag "${FULL_IMAGE}" \
  --tag "${LATEST_IMAGE}" \
  --push \
  "${REPO_ROOT}"

echo "==> Done. Pushed ${FULL_IMAGE} and ${LATEST_IMAGE}"