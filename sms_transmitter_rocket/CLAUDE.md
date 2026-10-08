# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Rust backend for relaying SMS messages through Android devices using Firebase Cloud Messaging (FCM). Built with Rocket 0.5, async-graphql 7, and SeaORM 1 over SQLite.

This directory is part of a monorepo rooted one level up (`sms_transmitter/`), alongside `sms_transmitter_Android/` — the app that receives FCM messages and physically sends the SMS. Git operations span both projects.

### Message flow

1. A client calls the `requestSms` mutation (recipient number + message).
2. The server picks a sender phone number: among FCM tokens seen within the last 90 days (`last_access_time`), whose phone number still has daily/monthly quota, choosing the highest `availability_score()` (daily ratio ×10 + monthly ratio; limit 0 = unlimited, which scores lowest).
3. An `sms_requests` row is created with status `Sending`, then the FCM data message is dispatched in a spawned tokio task (keys: `senderPhoneNumber`, `phoneNumber`, `message`, `requestId`). If FCM fails, the row is flipped to `Failed` asynchronously.
4. The Android app sends the SMS and calls `reportSmsResult`. Sent-counts are incremented only then — not at request time. Daily/monthly counter resets are lazy, derived from `last_daily_reset`/`last_monthly_reset` dates whenever counts are read or incremented.
5. If `callbackUrl` was provided, the server GETs it and stores the response (`callback_response_code`/`callback_response_body`; `-1` on transport error, body capped at 16 KB).

Callback URLs are SSRF-guarded (`validate_callback_url` / `build_callback_client_with_resolved_host` in `src/graphql/sms_request.rs`): http/https only, localhost and private/link-local IPs rejected, and domains are pre-resolved with every address validated before connecting (prevents DNS rebinding).

## Commands

```bash
cargo run        # start server; runs DB migrations automatically
cargo check      # fast compile check
cargo clippy     # lint
```

- Startup fails without a Firebase service-account JSON at `firebase-credentials.json` (project root) or the path given by `FIREBASE_CREDENTIALS_PATH`.
- Config loads from `.env` via dotenvy: `DATABASE_URL` (default `sqlite://db/sms_transmitter.db?mode=rwc`), `FIREBASE_CREDENTIALS_PATH`.
- Listen address/port come from `Rocket.toml` (currently `0.0.0.0:54323` — the README's `localhost:8000` is stale). GraphiQL UI at `/graphiql`, API at `/graphql`.
- No test suite exists yet.
- Release images: `docker/build_and_push.sh` multi-arch-builds `rouddy/sms_transmitter` tagged with the version from `Cargo.toml` and pushes to Docker Hub.

## Architecture

Startup (`src/main.rs`): load `.env` → init tracing → read env config → connect DB → run migrations → build `FirebaseClient` from service-account JSON → assemble the async-graphql `Schema` with `DatabaseConnection` and `Arc<FirebaseClient>` injected as schema data → mount `/graphql` routes.

Layers:

- `src/entity/` — SeaORM entities (`fcm_token`, `phone_number`, `sms_request`). Domain logic lives on the models themselves: quota math (`can_send_now`, `availability_score`, `increment_sent_counts`, lazy resets) is in `entity/phone_number.rs`.
- `src/graphql/` — resolvers merged into `QueryRoot`/`MutationRoot` (`mod.rs`); each resolver pulls `DatabaseConnection` / `Arc<FirebaseClient>` out of the GraphQL context. Entity models convert to GraphQL DTOs via `From` impls; datetimes become strings, and client-facing sent-counts are computed as *effective* values (shown as 0 once the reset window has passed even though the DB retains the count).
- `src/firebase.rs` — `FirebaseClient`: OAuth2 JWT-bearer flow against the service account (access token cached until near expiry), then FCM v1 HTTP `messages:send`.
- `src/migration/` — SeaORM migrations, applied automatically at startup.

Status values (`Sending`/`Sent`/`Failed`) are plain strings in SQLite, mapped to the `SmsStatus` enum in the GraphQL layer.

Phone numbers are globally unique across devices. `registerFcmToken` upserts by token: refreshes device info, deletes that token's numbers missing from the input while preserving counts for kept ones, and reassigns a number held by another token to this one (resetting its counts).

## Conventions

- New migration: create `src/migration/m{YYYYMMDD}_{NNNNNN}_<name>.rs` (`{NNNNNN}` zero-padded sequence), make `MigrationName::name()` return the exact module name, implement `MigrationTrait`, and register it in `src/migration/mod.rs`. Schema changes SQLite can't do with ALTER TABLE use raw SQL via `execute_unprepared` with `PRAGMA foreign_keys` toggled off around table rebuilds.
- Commit messages mix English and Korean, often with `feat:`/`fix:`/`docs:` prefixes. Development happens on `develop`; PRs target `main`.
