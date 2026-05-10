# SMS Transmitter Rocket

A Rust backend for relaying SMS messages via Android devices using Firebase Cloud Messaging (FCM).

## How It Works

1. A client calls the `requestSms` GraphQL mutation with a phone number and message.
2. The server selects an available Android device (by FCM token) that has remaining daily/monthly quota.
3. The server sends an FCM data message to the device with keys `phoneNumber`, `message`, and optionally `callback`.
4. The Android app receives the FCM message, sends the SMS, and calls `reportSmsResult` on this server's GraphQL endpoint to report success or failure.

## Setup

1. Copy `.env.example` to `.env` and fill in your values.
2. Place your Firebase service account JSON at the path set in `FIREBASE_CREDENTIALS_PATH`.
3. Run the server:

```bash
cargo run
```

The server runs migrations automatically on startup.

## GraphQL API

Navigate to `http://localhost:8000/graphiql` for the interactive explorer.

### Mutations

#### Register a device

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
    dailyRemaining
    monthlyRemaining
  }
}
```

#### Send an SMS

```graphql
mutation {
  requestSms(input: {
    phoneNumber: "+1234567890"
    message: "Hello from the server!"
    callbackUrl: "https://example.com/callback"   # optional
  }) {
    id
    status
    assignedDeviceId
  }
}
```

#### Report result (called by Android app)

```graphql
mutation {
  reportSmsResult(input: {
    requestId: "<UUID>"
    success: true
    error: null
  })
}
```

### Queries

```graphql
query {
  fcmTokens {
    id
    token
    deviceInfo
    dailyRemaining
    monthlyRemaining
  }
}

query {
  smsRequest(id: "<UUID>") {
    id
    status
    phoneNumber
    error
  }
}
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `sqlite://sms_transmitter.db?mode=rwc` | SeaORM database URL |
| `FIREBASE_CREDENTIALS_PATH` | `firebase-credentials.json` | Path to Firebase service account JSON |
| `FIREBASE_PROJECT_ID` | *(required)* | Firebase project ID |
