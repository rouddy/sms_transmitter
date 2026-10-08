# sms_transmitter_Android

Firebase Cloud Messaging(FCM) data message를 수신해 다음 동작을 수행하는 Android 앱입니다.

- key `phoneNumber`: SMS 수신 번호
- key `senderPhoneNumber`: 발신에 사용할 등록 번호
- key `message`: SMS 본문으로 발송
- key `requestId`: 서버로 결과 보고 시 사용
- 번호별 한도를 설정하고 SMS 전송 결과를 SQLite에 저장한 뒤 메인 화면/오버레이에서 번호별 사용량 확인

## FCM data payload 예시

```json
{
  "to": "<device_token>",
  "data": {
    "senderPhoneNumber": "01099998888",
    "phoneNumber": "01012345678",
    "message": "테스트 메시지",
    "requestId": "<UUID>"
  }
}
```

## 사전 준비

- `SEND_SMS` 권한 허용
- 듀얼심 번호 자동 등록을 사용하려면 `READ_PHONE_NUMBERS` 권한 허용
- Firebase Console에서 발급한 `google-services.json` 파일을 `app/google-services.json` 경로에 추가
- Environment variable에 SMS_TRANSMITTER_BASE_URL와 SMS_TRANSMITTER_PORT를 설정(설정하지 않으면 ``http://{Build중인 pc의 ip}``, 54323으로 동작)
- 서버 인증용 개인키 준비: `sms_transmitter_rocket/script/generate_keys.sh`로 생성한 `sms_transmitter_rocket/keys/private_key.pem`이 빌드 시 APK(BuildConfig)에 포함됩니다.
  다른 위치의 키를 쓰려면 `SMS_TRANSMITTER_PRIVATE_KEY_PATH` 환경변수에 경로를 설정하세요. 키가 없으면 빌드가 실패합니다.
  - 앱은 모든 서버 요청에 이 키로 서명한 JWT(`Authorization: Bearer ...`)를 붙입니다.
  - 키가 포함된 APK는 키와 같은 권한을 가지므로 신뢰하는 기기에만 설치하고 공개 배포하지 마세요.
  - 서버 키를 교체(`generate_keys.sh --rotate`)하면 APK를 다시 빌드해 설치해야 합니다.
