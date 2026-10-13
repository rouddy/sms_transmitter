# SMS Transmitter Rocket

A Rust backend for relaying SMS messages via Android devices using Firebase Cloud Messaging (FCM).

## How It Works

1. A client calls the `requestSms` GraphQL mutation with a phone number and message.
2. The server selects an available sender phone number registered under an Android device that has remaining daily/monthly quota.
3. The server sends an FCM data message to the device with keys `senderPhoneNumber`, `phoneNumber`, `message`, and `requestId`.
4. The Android app receives the FCM message, sends the SMS, and calls `reportSmsResult` on this server's GraphQL endpoint to report success or failure.
5. When `reportSmsResult` is received, the server calls `callbackUrl` (if provided in the original request).

## Setup

1. Copy `.env.example` to `.env` and fill in your values.
2. Place your Firebase service account JSON at the path set in `FIREBASE_CREDENTIALS_PATH`.
  If `firebase-credentials.json` is missing from the project root and `FIREBASE_CREDENTIALS_PATH` is not set to another valid file, the server will fail to start.
3. Generate the API authentication key pair:

```bash
./script/generate_keys.sh
```

  This creates `keys/private_key.pem` and `keys/public_key.pem` (both git/docker-ignored).
  If `public_key.pem` is missing from `AUTH_KEYS_DIR`, the server will fail to start.
4. Run the server:

```bash
cargo run
```

The server runs migrations automatically on startup.

## Authentication

Every `/graphql` request (GET and POST) must carry a short-lived ES256 JWT signed with the shared private key:

```
Authorization: Bearer <JWT>
```

- Header: `{"alg":"ES256","typ":"JWT"}`
- Claims: `iat`, `exp` (required, `exp - iat <= 300` seconds), `jti` (optional, recommended)
- 60 seconds of clock skew are tolerated.
- Missing or invalid tokens get `401 Unauthorized`. `/graphiql` itself is not protected, but its requests need the header (set it in the GraphiQL *Headers* tab).

Anyone holding `private_key.pem` can call every API, so share it only with trusted callers.
The Android app embeds it at build time (see `sms_transmitter_Android/README.md`).

Create a token for testing:

```bash
curl -H "Authorization: Bearer $(./script/make_jwt.sh)" \
     -H 'Content-Type: application/json' \
     -d '{"query":"{ phoneNumbers { phoneNumber } }"}' \
     http://localhost:54323/graphql
```

### Key rotation

```bash
./script/generate_keys.sh --rotate
```

1. The old pair is backed up to `keys/backup/<timestamp>/`, and the old public key is kept as `keys/public_key.previous.pem`.
2. Restart the server; it accepts tokens signed with either key.
3. Rebuild the Android APK and update every API caller with the new `private_key.pem`.
4. Delete `keys/public_key.previous.pem` and restart the server.

Rotation is not needed on a schedule; rotate when the private key may have leaked (lost phone, shared APK, departed member, etc.).

## Docker

The image never contains keys. Mount the key directory at `/app/keys` (only `public_key.pem` is needed on the server):

```bash
./script/generate_keys.sh
docker compose -f docker/docker-compose.yml up -d
```

or

```bash
docker run -d -p 54323:54323 \
  -e ROCKET_ADDRESS=0.0.0.0 -e ROCKET_PORT=54323 \
  -e DATABASE_URL='sqlite:///app/db/sms_transmitter.db?mode=rwc' \
  -e FIREBASE_CREDENTIALS_PATH=/app/secrets/firebase-credentials.json \
  -v "$PWD/db:/app/db" \
  -v "$PWD/firebase-credentials.json:/app/secrets/firebase-credentials.json:ro" \
  -v "$PWD/keys:/app/keys:ro" \
  rouddy/sms_transmitter:latest
```

For Kubernetes, see `k8s/README.md`.

## GraphQL API

Navigate to `http://localhost:54323/graphiql` for the interactive explorer (add the `Authorization` header described above).

### FCM Token

`FcmToken` fields:

- `id`
- `token`
- `deviceInfo`
- `phoneNumbers`
- `createdAt`
- `updatedAt`

`PhoneNumber` fields:

- `id`
- `fcmTokenId`
- `phoneNumber`
- `dailyLimit`
- `monthlyLimit`
- `dailySentCount`
- `monthlySentCount`
- `dailyRemaining`
- `monthlyRemaining`
- `lastDailyReset`
- `lastMonthlyReset`

`RegisterFcmTokenInput` fields:

- `token`
- `deviceInfo`
- `phoneNumbers`

Register or update a device token:

```graphql
mutation {
  registerFcmToken(input: {
    token: "<FCM_TOKEN>"
    deviceInfo: "Pixel 7"
    phoneNumbers: [
      { phoneNumber: "01012345678", dailyLimit: 100, monthlyLimit: 2000 }
      { phoneNumber: "01099998888", dailyLimit: 0, monthlyLimit: 0 }
    ]
  }) {
    id
    token
    deviceInfo
    phoneNumbers {
      id
      phoneNumber
      dailyLimit
      monthlyLimit
      dailySentCount
      monthlySentCount
      dailyRemaining
      monthlyRemaining
      lastDailyReset
      lastMonthlyReset
    }
    createdAt
    updatedAt
  }
}
```

List registered device tokens:

```graphql
query {
  fcmTokens {
    id
    token
    deviceInfo
    phoneNumbers {
      id
      phoneNumber
      dailyLimit
      monthlyLimit
      dailySentCount
      monthlySentCount
      dailyRemaining
      monthlyRemaining
      lastDailyReset
      lastMonthlyReset
    }
    createdAt
    updatedAt
  }
}
```

Delete a device token:

```graphql
mutation {
  deleteFcmToken(tokenId: "<UUID>")
}
```

Note: registering the same `token` again updates `deviceInfo`, `phoneNumbers`, per-phone-number limits, and `updatedAt`. Existing sent counts are preserved for unchanged phone numbers.

### SMS Request

`SmsStatus` values:

- `Sending`
- `Sent`
- `Failed`

`SmsRequest` fields:

- `id`
- `phoneNumber`
- `message`
- `callbackUrl`
- `callbackResponseCode`
- `callbackResponseBody`
- `status`
- `assignedDeviceId`
- `assignedPhoneNumberId`
- `error`
- `createdAt`
- `updatedAt`

`RequestSmsInput` fields:

- `phoneNumber`
- `message`
- `callbackUrl`

`ReportSmsResultInput` fields:

- `requestId`
- `success`
- `error`

Create an SMS request:

```graphql
mutation {
  requestSms(input: {
    phoneNumber: "01012345678"
    message: "Hello from the server!"
    callbackUrl: "https://example.com/callback"
  }) {
    id
    phoneNumber
    message
    callbackUrl
    callbackResponseCode
    callbackResponseBody
    status
    assignedDeviceId
    assignedPhoneNumberId
    error
    createdAt
    updatedAt
  }
}
```

Report the SMS delivery result from the Android app:

```graphql
mutation {
  reportSmsResult(input: {
    requestId: "<UUID>"
    success: true
    error: null
  })
}
```

Fetch a single SMS request:

```graphql
query {
  smsRequest(id: "<UUID>") {
    id
    phoneNumber
    message
    callbackUrl
    callbackResponseCode
    callbackResponseBody
    status
    assignedDeviceId
    assignedPhoneNumberId
    error
    createdAt
    updatedAt
  }
}
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `sqlite://db/sms_transmitter.db?mode=rwc` | SeaORM database URL |
| `FIREBASE_CREDENTIALS_PATH` | `firebase-credentials.json` | Path to Firebase service account JSON (project ID is read from this file) |
| `AUTH_KEYS_DIR` | `keys` | Directory holding `public_key.pem` (required) and `public_key.previous.pem` (optional, during rotation) |

## Scripts

### Key Scripts

| Script | Description |
|---|---|
| `script/generate_keys.sh [--rotate]` | Create the shared ES256 key pair in `keys/` (or `KEYS_DIR`), or rotate it |
| `script/make_jwt.sh` | Print a JWT signed with `keys/private_key.pem` (`PRIVATE_KEY`, `TTL` env vars override) |

### Test SMS Script (`script/test_sens_sms.sh`)

`requestSms` (`request_sms`) GraphQL mutation을 호출하여 SMS 발송을 테스트하는 쉘 스크립트입니다.

```bash
# 기본 사용법 (기본 Base URL: http://localhost:54323, 기본 메시지: "문자 받으면 연락주세요")
./script/test_sens_sms.sh --phone 01012345678

# 메시지 및 Base URL 직접 지정
./script/test_sens_sms.sh http://localhost:54323 --phone 01012345678 --msg "인증번호: 123456"

# Callback URL 포함
./script/test_sens_sms.sh --phone 01012345678 --callback "https://example.com/callback"

# 도움말 확인
./script/test_sens_sms.sh --help
```

#### Parameters & Options

| 인자 / 옵션 | 필수 여부 | 기본값 | 설명 |
|---|---|---|---|
| `[BASE_URL]` / `--base-url`, `-b` | 선택 | `http://localhost:54323` | GraphQL 서버 Base URL (기본 위치 인자 또는 플래그로 지정 가능) |
| `--phone`, `-p` | **필수** | - | 수신자 전화번호 (누락 시 에러 발생) |
| `--msg`, `-m` | 선택 | `"문자 받으면 연락주세요"` | 전송할 SMS 메시지 내용 |
| `--callback`, `-c` | 선택 | - | SMS 발송 결과 리포트 수신용 Callback URL |
| `--dry-run` | 선택 | - | 실제 요청을 전송하지 않고 엔드포인트와 페이로드 JSON만 출력 |
| `--help`, `-h` | 선택 | - | 사용법 안내 출력 |

