# sms_transmitter_Android

Firebase Cloud Messaging(FCM) data message를 수신해 다음 동작을 수행하는 Android 앱입니다.

- key `phoneNumber`: SMS 수신 번호
- key `message`: SMS 본문으로 발송
- key `callback`: 발송 후 해당 URL 호출
- SMS 전송 결과 및 callback 결과를 SQLite에 저장하고 메인 화면에서 확인

## FCM data payload 예시

```json
{
  "to": "<device_token>",
  "data": {
    "phoneNumber": "01012345678",
    "message": "테스트 메시지",
    "callback": "https://example.com/callback"
  }
}
```

## 사전 준비

- `SEND_SMS` 권한 허용
- Firebase Console에서 발급한 `google-services.json` 파일을 `app/google-services.json` 경로에 추가
- Environment variable에 SMS_TRANSMITTER_BASE_URL와 SMS_TRANSMITTER_PORT를 설정(설정하지 않으면 http://0.0.0.0, 54323으로 동작)
