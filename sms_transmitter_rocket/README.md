# SMS Transmitter Rocket

A Rust backend for relaying SMS messages via Android devices using Firebase Cloud Messaging (FCM).

## How It Works

1. A client calls the `requestSms` GraphQL mutation with a phone number and message.
2. The server selects an available Android device (by FCM token) that has remaining daily/monthly quota.
3. The server sends an FCM data message to the device with keys `phoneNumber`, `message`, and `requestId`.
4. The Android app receives the FCM message, sends the SMS, and calls `reportSmsResult` on this server's GraphQL endpoint to report success or failure.
5. When `reportSmsResult` is received, the server calls `callbackUrl` (if provided in the original request).

## Setup

1. Copy `.env.example` to `.env` and fill in your values.
2. Place your Firebase service account JSON at the path set in `FIREBASE_CREDENTIALS_PATH`.
  If `firebase-credentials.json` is missing from the project root and `FIREBASE_CREDENTIALS_PATH` is not set to another valid file, the server will fail to start.
3. Run the server:

```bash
cargo run
```

The server runs migrations automatically on startup.

## GraphQL API

Navigate to `http://localhost:8000/graphiql` for the interactive explorer.

### FCM Token

`FcmToken` fields:

- `id`
- `token`
- `deviceInfo`
- `dailyLimit`
- `monthlyLimit`
- `dailySentCount`
- `monthlySentCount`
- `dailyRemaining`
- `monthlyRemaining`
- `createdAt`
- `updatedAt`

`RegisterFcmTokenInput` fields:

- `token`
- `deviceInfo`
- `dailyLimit`
- `monthlyLimit`

Register or update a device token:

```graphql
mutation {
  registerFcmToken(input: {
    token: "<FCM_TOKEN>"
    deviceInfo: "Pixel 7"
    dailyLimit: 100
    monthlyLimit: 2000
  }) {
    id
    token
    deviceInfo
    dailyLimit
    monthlyLimit
    dailySentCount
    monthlySentCount
    dailyRemaining
    monthlyRemaining
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
    dailyLimit
    monthlyLimit
    dailySentCount
    monthlySentCount
    dailyRemaining
    monthlyRemaining
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

Note: registering the same `token` again updates `deviceInfo`, `dailyLimit`, `monthlyLimit`, and `updatedAt`. Existing sent counts are preserved.

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
- `dailySentCount`
- `monthlySentCount`

Create an SMS request:

```graphql
mutation {
  requestSms(input: {
    phoneNumber: "+1234567890"
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
    dailySentCount: 1
    monthlySentCount: 10
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
    error
    createdAt
    updatedAt
  }
}
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `sqlite://sms_transmitter.db?mode=rwc` | SeaORM database URL |
| `FIREBASE_CREDENTIALS_PATH` | `firebase-credentials.json` | Path to Firebase service account JSON (project ID is read from this file) |
