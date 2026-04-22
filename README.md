# sms_transmitter_Android

Firebase Cloud Messaging(FCM) data message를 수신해 다음 동작을 수행하는 Android 앱입니다.

- key `message`: SMS 본문으로 발송
- key `callback`: 발송 후 해당 URL 호출

## FCM data payload 예시

```json
{
  "to": "<device_token>",
  "data": {
    "message": "테스트 메시지",
    "callback": "https://example.com/callback"
  }
}
```

## 사전 준비

- 앱 실행 후 SMS 수신 번호를 입력하고 저장
- `SEND_SMS` 권한 허용
- Firebase Console에서 발급한 `google-services.json` 파일을 `app/google-services.json` 경로에 추가
