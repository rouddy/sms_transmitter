# Build stages
# Run from the crate root (sms_transmitter_rocket/):
#   docker build -f docker/Dockerfile .
#
# cargo-chef 로 의존성 빌드를 별도 레이어로 분리한다. Cargo.toml/Cargo.lock 이
# 바뀌지 않으면 의존성 레이어는 캐시를 재사용하고 앱 크레이트만 다시 컴파일한다.
FROM rust:1.94.1-slim AS chef

# Install system dependencies required by the native build (OpenSSL, etc.)
RUN apt-get update && apt-get install -y \
    cmake \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

RUN cargo install cargo-chef --locked

WORKDIR /app

# 의존성 목록(recipe.json)만 추출한다. 소스가 바뀌어도 recipe 내용이 같으면
# 아래 builder 의 cook 레이어는 캐시된다.
FROM chef AS planner
COPY . /app
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
COPY --from=planner /app/recipe.json recipe.json
RUN cargo chef cook --release --recipe-path recipe.json

# Copy the crate sources (build context = sms_transmitter_rocket/)
COPY . /app

# Build the sms_transmitter_rocket binary in release mode
RUN cargo build --release --package sms_transmitter_rocket

# Runtime stage
FROM debian:trixie-slim AS runtime

RUN apt-get update && apt-get install -y \
    libssl3 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/release/sms_transmitter_rocket /app/sms_transmitter_rocket

# Auth key pair is never baked into the image; mount the directory created by
# script/generate_keys.sh here (only public_key.pem is required on the server).
ENV AUTH_KEYS_DIR=/app/keys
VOLUME ["/app/keys"]

ENTRYPOINT ["/app/sms_transmitter_rocket"]
