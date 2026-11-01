# sms_transmitter_Android

An Android app that receives Firebase Cloud Messaging (FCM) data messages and does the following:

- key `phoneNumber`: SMS recipient number
- key `senderPhoneNumber`: registered number to send from
- key `message`: sent as the SMS body
- key `requestId`: used when reporting the result to the server
- Sets per-number limits, stores SMS send results in SQLite, and shows per-number usage on the main screen and overlay

## Example FCM data payload

```json
{
  "to": "<device_token>",
  "data": {
    "senderPhoneNumber": "01099998888",
    "phoneNumber": "01012345678",
    "message": "Test message",
    "requestId": "<UUID>"
  }
}
```

## Prerequisites

- Grant the `SEND_SMS` permission
- To register dual-SIM numbers automatically, grant the `READ_PHONE_NUMBERS` permission
- Add the `google-services.json` file issued from the Firebase Console at `app/google-services.json`
- Set the SMS_TRANSMITTER_BASE_URL and SMS_TRANSMITTER_PORT environment variables (if unset, the app uses ``http://{IP of the building PC}`` and 54323)
- Prepare the private key for server authentication: `sms_transmitter_rocket/keys/private_key.pem`, created by `sms_transmitter_rocket/script/generate_keys.sh`, is embedded in the APK (BuildConfig) at build time.
  To use a key from another location, set its path in the `SMS_TRANSMITTER_PRIVATE_KEY_PATH` environment variable. The build fails if the key is missing.
  - The app attaches a JWT signed with this key (`Authorization: Bearer ...`) to every server request.
  - An APK containing the key has the same privileges as the key itself, so install it only on trusted devices and never distribute it publicly.
  - If the server key is rotated (`generate_keys.sh --rotate`), rebuild and reinstall the APK.
