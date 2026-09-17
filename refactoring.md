# Refactoring 정리

`sms_transmitter_rocket`(Rust)과 `sms_transmitter_Android`(Kotlin)를 정독한 뒤, 우선순위별로 리팩토링 항목을 정리한 문서입니다.

우선순위 기준:
- **P0** — 정확성/안정성에 직접 영향이 있거나, 동일 로직의 대규모 중복으로 유지보수 위험이 큰 항목
- **P1** — 구조 개선·가독성·중복 제거 등 유지보수성 향상 항목
- **P2** — 마이너 폴리시·관례 일관성 항목

> P0 항목은 모두 처리 완료(이력은 git 참조). 아래는 후속 항목이며, 완료된 항목은 별도로 표시합니다.

---

## 요약

| 우선순위 | 항목 | 영역 | 핵심 문제 |
|---|---|---|---|
| P1 | GraphQL 호출 보일러플레이트 공통화 | Android | subscribeOn/observeOn 중복 |
| P1 | 하드코딩된 한국어 문자열 | Android | `SmsHistoryAdapter` |
| P1 | GraphQL 응답 `Map<String,Any?>` 캐스팅 | Android | 타입 안전성 부족 |
| P1 | ✅ `status` 문자열 타이핑 (완료) | Rocket | SeaORM enum 적용, silent fallback 제거 |
| P1 | 설정 마이그레이션 잔재 정리 | Android | `SmsLimitSettings` legacy 키 |
| P1 | 피드백 응답시 발신 카운트 증가 분기 | Rocket | 정상/fallback 경로 분기 |
| P1 | `SettingsActivity` 분리 | Android | 단일 액티비티의 다중 책임 |
| P2 | Retrofit 인스턴스 무한 캐시 | Android | `RetrofitClientProvider` |
| P2 | `notifyDataSetChanged()` → DiffUtil | Android | RecyclerView 성능 |
| P2 | `sendTextMessage` 전송 결과 확인 부재 | Android | "성공" != 실제 수신 |
| P2 | 토큰 뮤텍스 홀딩 / HTTP 타임아웃 부재 | Rocket | `firebase.rs` |
| P2 | 빌드타임 로컬 IP 감지 | Android | `build.gradle.kts` |
| P2 | 부가 정리 | Rocket | `db.rs`·매직 상수·정규화 공유 |

---

## P1 — 중간 우선순위

### 1. GraphQL 호출 보일러플레이트 공통화 (Android)

- **위치**: `MainActivity` / `SettingsActivity` / `FirebaseMessageReceiverService` 전반
- **문제**:
  - `.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({...}, {...})` + 스케줄러/디스포저 관리 패턴이 반복됩니다.
  - 에러 시 토스트(`R.string.server_connection_failed`)를 띄우는 파편화된 처리도 여러 곳에 흩어져 있습니다.
- **개선 방향**:
  - 네트워크 호출 전용 확장 함수(`Single<T>.executeNetwork(onSuccess, onError)`)나 공용 `Disposable` 관리 헬퍼를 도입합니다.
  - 더 근본적으로는 RxJava3를 **Kotlin Coroutines + `suspendCancellableCoroutine`(또는 Retrofit suspend 지원)**로 전환하는 것도 고려할 만합니다.

### 2. 하드코딩된 한국어 문자열 → strings.xml (Android)

- **위치**: `SmsHistoryAdapter.kt` `bind` (45~62행)
- **문제**:
  - `"시간: ..."`, `"수신번호: ..."`, `"메시지: ..."`, `"SMS: $smsStatus"` 가 코드에 하드코딩되어 있습니다. 나머지 문자열은 대부분 `strings.xml`에 있는데 이 파일만 예외로, i18n이 깨져 있습니다.
- **개선 방향**:
  - 전부 `strings.xml` 리소스로 옮기고 `getString(R.string...., args)` 포맷을 사용합니다. 기존 `sms_success`/`sms_failure` 리소스도 이미 있으므로 `"SMS: "` 접두어 문구만 리소스로 추가하면 됩니다.

### 3. GraphQL 응답 타입 안전화 (Android)

- **위치**: `GraphQlClient.kt` `findFcmTokenId`, `GraphQlApiService.kt` `GraphQlResponse`
- **문제**:
  - `GraphQlResponse.data`가 `Map<String, Any?>?`이라서 응답을 `as? List<*>` / `as? Map<*,*>` 로 수동 캐스팅하고 있습니다. 필드명 오타나 타입 불일치가 런타임에서야 드러납니다.
- **개선 방향**:
  - 쿼리별 응답 DTO를 정의하고 Gson(또는 kotlinx.serialization)으로 매핑합니다.
  - 예: `data class FcmTokensResponse(val fcmTokens: List<FcmTokenDto>)`, `FcmTokenDto(val id: String, val token: String)`.
  - `registerFcmToken`이 `{ id }`를 반환하는데 현재 버리고 있으므로 DTO로 받아 저장하면, `fcmTokens` 전체 조회로 id를 역추적하는 `deleteFcmTokenByToken` 구조를 개선할 수 있습니다.

### 4. `status` 문자열 타이핑 개선 (Rocket) — ✅ 완료

- **위치**: `sms_transmitter_rocket/src/entity/sms_request.rs`, `src/graphql/sms_request.rs`
- **기존 문제**: 상태를 문자열로 처리하고 알 수 없는 값을 조용히 `Failed`로 매핑해 데이터 불일치를 숨겼습니다.
- **적용 내용**:
  - 엔티티에 `EnumIter` + `DeriveActiveEnum` 기반 `SmsStatus`를 정의하고 `Model.status`를 `String`에서 enum으로 변경했습니다.
  - 요청 생성·FCM 실패·결과 보고의 상태 저장에 enum을 사용하고 `STATUS_*` 상수와 `from_str` fallback을 제거했습니다.
  - GraphQL enum은 기존 API를 유지하며, 엔티티 enum에서 모든 variant를 명시적으로 매핑합니다. 알 수 없는 DB 값은 SeaORM 역직렬화 단계에서 오류가 발생하고 조회 resolver의 `?`를 통해 GraphQL 오류로 전달되므로 DTO 변환은 `From`으로 충분합니다.
- **호환성**: SQLite 문자열 컬럼과 기존 저장값(`Sending`/`Sent`/`Failed`), GraphQL 값(`SENDING`/`SENT`/`FAILED`)을 유지하므로 DB 마이그레이션은 필요 없습니다. DB 자체에 CHECK 제약을 추가한 것은 아니므로 외부 SQL로 잘못된 값을 넣으면 조회 시 오류가 납니다.
- **검증**: DB 값 매핑, SQLite의 정상값 역직렬화·알 수 없는 값 거부, GraphQL enum 값 유지 테스트를 추가했습니다. `cargo check` 및 `cargo test` 통과(전체 21개).

### 5. 설정 마이그레이션 잔재 정리 (Android)

- **위치**: `SmsLimitSettings.kt` — `readLegacyPhoneNumbers`, `readLegacyLimit`, `KEY_DAILY_LIMIT`/`KEY_MONTHLY_LIMIT`/`KEY_PHONE_NUMBERS` legacy 키
- **문제**:
  - 과거 단일 발신번호+단일 한도 구조의 legacy 키를 여전히 읽고, `savePhoneNumberConfigs`가 새 JSON(`KEY_PHONE_NUMBER_CONFIGS`)과 legacy(`KEY_PHONE_NUMBERS`)를 **둘 다** 기록합니다. 신규 JSON이 유일 소스가 된 지 오래면 legacy 경로는 데드코드에 가깝습니다.
- **개선 방향**:
  - legacy 읽기·쓰기 경로를 제거하고 `remove(...)`로 한 번 정리하는 절차를 문서화한 뒤, `getConfig`/`savePhoneNumberConfigs`를 신규 JSON 단일 소스로 단순화합니다.

### 6. 피드백 응답시 발신 카운트 증가 분기 정리 (Rocket)

- **위치**: `sms_transmitter_rocket/src/graphql/sms_request.rs` `report_sms_result`
- **문제**:
  - `assigned_phone_number_id`로 증가하는 정상 경로와, 그것이 없을 때 `assigned_token_id`로 fallback 발신자를 찾아 증가하는 예외 경로가 따로 작성되어 있어 읽기 어렵습니다.
- **개선 방향**:
  - "증가 대상 `phone_number::Model` 해석"을 하나의 헬퍼 함수로 추출하고 두 경로를 합칩니다. fallback은 주석으로 왜 필요한지 명시합니다(스키마 이행기 데이터 호환).

### 7. `SettingsActivity` 분리 (Android)

- **위치**: `SettingsActivity.kt`
- **문제**:
  - 액티비티 하나가 (1) 번호별 한도 UI를 프로그래매틱하게 생성·바인딩하고 (2) 입력 검증을 하고 (3) 오버레이 토글·권한 흐름을 관리하고 (4) 서버 등록까지 수행합니다. 한 파일이 네 가지 책임을 지니고 있습니다.
- **개선 방향**:
  - 번호별 한도 입력 UI 생성 로직(`createPhoneNumberLimitRow` 등)과 검증/파싱 로직(`parseLimitInput`, `collectPhoneNumberConfigs`)을 별도 클래스나 위젯으로 추출합니다.
  - 오버레이 권한/토글 상태 머신(`OverlayEnablementStatus` 관련)도 별도 헬퍼로 분리하면 액티비티는 조립 역할만 남습니다.

---

## P2 — 낮은 우선순위

### 8. Retrofit 인스턴스 무한 캐시 (Android)

- **위치**: `RetrofitClientProvider.kt`
- **문제**: `cache: mutableMapOf<String, Retrofit>`이 baseUrl 변경 시 계속 누적되어, URL을 자주 바꾸면 인스턴스가 무한히 쌓입니다(현재 규모에서는 미미하지만 원칙상 위험).
- **개선 방향**: 고정 크기 LRU, 또는 실행 중 서버 URL 변경이 드물다는 전제 하에 마지막 1개만 유지하도록 단순화합니다.

### 9. `notifyDataSetChanged()` → `DiffUtil` (Android)

- **위치**: `SmsHistoryAdapter.kt` `submitList`
- **문제**: 목록 갱신 시 전체 리바인딩을 유발합니다.
- **개선 방향**: `ListAdapter` + `DiffUtil.ItemCallback`로 교체합니다(갱신이 잦지는 않지만 관례상 개선 여지).

### 10. `sendTextMessage` 전송 결과 확인 부재 (Android)

- **위치**: `FirebaseMessageReceiverService.kt` `sendSmsSafely`
- **문제**: `sendTextMessage`/`sendMultipartTextMessage`를 PendingIntent(sent/delivered) 없이 호출해 "라디오에 전달됨"을 곧 "성공"으로 처리합니다. 실제 수신·실패 여부가 반영되지 않아 서버에 잘못된 `success=true`를 보고할 수 있습니다.
- **개선 방향**: sent/delivered PendingIntent를 전달해 결과를 비동기로 수신하고, 그 결과를 기준으로 `reportSmsResult`를 호출하도록 변경합니다(정확성 개선이므로 사실상 P0에 가까운 논의 대상).

### 11. 토큰 뮤텍스 홀딩 & HTTP 타임아웃 부재 (Rocket)

- **위치**: `sms_transmitter_rocket/src/firebase.rs`
- **문제**:
  - `get_access_token`이 OAuth 토큰 갱신 HTTP 호출 **전 구간 동안 뮤텍스를 들고 있어** 동시 요청이 직렬화됩니다.
  - `send_sms_request`의 FCM HTTP 호출과 토큰 발급 호출에 명시적 타임아웃이 없습니다(콜백 호출은 `Duration::from_secs(5)`가 있으나 FCM은 없음).
- **개선 방향**: 토큰 갱신은 뮤텍스 밖에서 수행하고 캐시 교체만 원자적으로 처리하거나, `tokio::sync::OnceCell`/`RwLock`으로 전환합니다. FCM 발송에도 타임아웃·재시도/백오프를 추가합니다.

### 12. 빌드타임 로컬 IP 자동 감지 (Android)

- **위치**: `sms_transmitter_Android/app/build.gradle.kts` `getEn0IpAddress`
- **문제**: 빌드 시점에 macOS `en0` 인터페이스 IP를 감지해 `BASE_URL`로 박아 넣는 로직이 빌드 스크립트와 개발 머신을 결합시킵니다. 다른 환경/CI 빌드에서는 의도와 다르게 동작할 수 있습니다(문서에 환경변수 우선이라고는 되어 있음).
- **개선 방향**: 기본값을 명시적 placeholder/환경변수 필수로 바꾸고, "자동 감지"는 별도 dev 전용 설정으로 분리합니다.

### 13. 부가 정리 (Rocket)

- `db.rs`는 7줄짜리 단일 함수 래퍼라 `main.rs`에 인라인하거나 그대로 두되 `config`처럼 용도를 명확히 하는 수준으로 정리 가능.
- `ACTIVE_TOKEN_SECS = 7_776_000`(90일) 같은 매직 상수는 `chrono::Duration::days(90)`로 표현해 의도를 명시.
- `fcm_token.rs`의 `register_fcm_token`과 Android `PhoneNumberResolver.normalizePhoneNumber`는 서로 다른 "전화번호 정규화" 구현이라, 규칙(트림·공백/하이픈 제거 등)이 벌어지지 않도록 양쪽 정규화 규칙을 문서화·공유할 필요(**cross-cutting**).

---

## 제안 실행 순서

1. **Rocket 마무리**: `status` enum 전환(4) 완료 → 피드백 발신 카운트 분기 정리(6).
2. **Android 구조 정리**: GraphQL 보일러플레이트 공통화(1) → 타입 안전화(3) → 하드코딩 문자열(2) 순으로 연결.
3. **타입/문자열/설정 정리**: 하드코딩 문자열(2)·legacy 잔재(5) 제거 (`status`(4)는 완료).
4. **마이너 폴리시**: P2 항목(8~13) — DiffUtil, Retrofit 캐시, 타임아웃, 빌드 설정 등 후순위.

> 참고: `sendTextMessage` 전송 결과 확인(10)은 "리팩토링"이라기보다 정확성 이슈이므로 단독으로 다루는 편이 안전합니다.