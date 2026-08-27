# Build stage
# Run from the crate root (sms_transmitter_rocket/):
#   docker build -f docker/Dockerfile .
FROM rust:1.94.1-slim AS builder

WORKDIR /app

# Install system dependencies required by the native build (OpenSSL, etc.)
RUN apt-get update && apt-get install -y \
    cmake \
    pkg-config \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

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

ENTRYPOINT ["/app/sms_transmitter_rocket"]
