# Refactoring 정리

`sms_transmitter_rocket`(Rust)과 `sms_transmitter_Android`(Kotlin)를 정독한 뒤, 우선순위별로 리팩토링 항목을 정리한 문서입니다.

우선순위 기준:
- **P0** — 정확성/안정성에 직접 영향이 있거나, 동일 로직의 대규모 중복으로 유지보수 위험이 큰 항목
- **P1** — 구조 개선·가독성·중복 제거 등 유지보수성 향상 항목
- **P2** — 마이너 폴리시·관례 일관성 항목

---

## 요약

| 우선순위 | 항목 | 영역 | 핵심 문제 |
|---|---|---|---|
| P0 | 핵심 도메인 로직 단위 테스트 부재 | Rocket | 쿼터/리셋 로직 무검증 |
| P0 | FCM 등록 로직 3중 중복 | Android | MainActivity / SettingsActivity / Service |
| P0 | 사용량 집계 로직 3중 중복 | Android | MainActivity / OverlayService / Service |
| P0 | 콜백 응답 저장 로직 중복 | Rocket | `report_sms_result` 성공/실패 가지 |
| P0 | 유효 발신 카운트 로직 중복 | Rocket | `From` impl vs `Model` 메서드 |
| P1 | GraphQL 호출 보일러플레이트 공통화 | Android | subscribeOn/observeOn 중복 |
| P1 | 하드코딩된 한국어 문자열 | Android | `SmsHistoryAdapter` |
| P1 | GraphQL 응답 `Map<String,Any?>` 캐스팅 | Android | 타입 안전성 부족 |
| P1 | `status` 문자열 타이핑 | Rocket | `"Sending"/"Sent"/"Failed"` 오타·silent fallback |
| P1 | 설정 마이그레이션 잔재 정리 | Android | `SmsLimitSettings` legacy 키 |
| P2 | Retrofit 인스턴스 무한 캐시 | Android | `RetrofitClientProvider` |
| P2 | `notifyDataSetChanged()` → DiffUtil | Android | RecyclerView 성능 |
| P2 | `sendTextMessage` 전송 결과 확인 부재 | Android | "성공" != 실제 수신 |
| P2 | 토큰 뮤텍스 홀딩 / HTTP 타임아웃 부재 | Rocket | `firebase.rs` |
| P2 | 빌드타임 로컬 IP 감지 | Android | `build.gradle.kts` |

---

## P0 — 높은 우선순위

### 1. 핵심 도메인 로직 단위 테스트 부재 (Rocket)

- **위치**: `sms_transmitter_rocket/src/entity/phone_number.rs` (쿼터/리셋 로직), 프로젝트 전체에 테스트 0건
- **문제**:
  - `effective_daily_sent_count` / `effective_monthly_sent_count` / `increment_sent_counts`의 리셋 경계(자정·월초) 로직은 이 서비스의 핵심 비즈니스 룰인데 **어떤 코드로도 검증되지 않습니다.** 리셋 시점 버그는 조용히 과금/차단 오동작으로 이어집니다.
  - CLAUDE.md에도 "No test suite exists yet"로 명시되어 있습니다. 도메인 로직은 순수 함수라 테스트가 가장 쉬운 부분입니다.
- **개선 방향**:
  - `entity/phone_number.rs`의 순수 메서드들(`daily_remaining`, `monthly_remaining`, `can_send_now`, `availability_score`, `increment_sent_counts`)에 유닛 테스트를 추가합니다.
  - 특히 `last_daily_reset`/`last_monthly_reset`이 "오늘"과 같거나 이전/이후일 때의 경계 케이스를 표 형태로 검증합니다.
  - `Local::now()`에 의존해 "오늘"을 구하는 부분을 메서드 파라미터(`today: NaiveDate`)로 주입받도록 리팩토링하면 테스트 가능성이 크게 올라갑니다 (아래 6번과 연결).

### 3. FCM 등록 로직 3중 중복 (Android)

- **위치**:
  - `MainActivity.kt` `registerFcmTokenWithServer` (224~251행)
  - `SettingsActivity.kt` `registerFcmTokenWithServer` (226~256행)
  - `FirebaseMessageReceiverService.kt` `onNewToken` (23~42행)
- **문제**:
  - "FCM 토큰 조회 → `deviceInfo` 생성 → `GraphQlClient.registerFcmToken` → RxJava 스케줄러 → 성공/실패 로그" 흐름이 세 곳에 거의 동일하게 복붙되어 있습니다.
  - `deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"`도 세 번 중복, `SmsLimitSettings` 생성·`getConfig()`·`initializePhoneNumbersIfNeeded` 호출도 반복됩니다.
  - 한 곳(예: 등록 실패 시 토스트·재시도 정책)이 바뀌면 세 곳을 모두 고쳐야 하는 위험이 있습니다.
- **개선 방향**:
  - `FcmTokenRegistrar`(또는 `FcmRegistrationManager`) 같은 단일 클래스를 만들어 `fun register(context)` 하나로 통합하고, 세 호출부는 이걸 위임하도록 바꿉니다.
  - `deviceInfo` 생성도 이 클래스(또는 공용 상수/헬퍼)로 이동합니다.
  - 이때 "등록 성공 시 서버가 반환한 token id를 저장"하는 로직도 함께 두면, 현재 `deleteFcmTokenByToken`이 `fcmTokens` 전체 조회로 id를 역추적하는 구조를 개선할 수 있습니다.

### 4. 사용량 집계·포맷 로직 3중 중복 (Android)

- **위치**:
  - `MainActivity.kt` `renderRemainingCounts` (170~210행) + `formatUsageLine` (212~224행)
  - `UsageOverlayService.kt` `refreshOverlay` (69~119행) + `formatUsageLine` (121~133행)
  - `FirebaseMessageReceiverService.kt` `checkLimitReachedError` (141~170행)
- **문제**:
  - "오늘/이번 달 시작·끝 millis 구하기 + `countSuccessfulSmsBetweenForSender` 호출 + 남은 수 계산 + `일간/월간` 문자열 포맷" 패턴이 세 곳에 중복되어 있습니다.
  - `getStartOfTodayMillis` / `getStartOfNextDayMillis` / `getStartOfMonthMillis` / `getStartOfNextMonthMillis` 호출 조합도 계속 반복됩니다.
- **개선 방향**:
  - `SmsUsageCalculator`(또는 `SmsUsageTracker`) 같은 컴포넌트를 만들어 `PhoneNumberLimitConfig` → `(오늘 발신수, 이번달 발신수, 남은 수)`를 산출하는 단일 API(`usageFor(phoneNumber, now)`)를 제공합니다.
  - `formatUsageLine`도 공용으로 옮기고, 세 호출부는 계산 결과를 받아 렌더링만 하도록 바꿉니다.
  - 한도 도달 체크(`checkLimitReachedError`)도 이 계산기를 재사용하도록 정리합니다.

### 5. 콜백 응답 저장 로직 중복 (Rocket)

- **위치**: `sms_transmitter_rocket/src/graphql/sms_request.rs` `report_sms_result` (281~359행)
- **문제**:
  - `invoke_callback_url` 성공 분기와 실패 분기에 "`find_by_id` → ActiveModel 갱신 → `callback_response_code`/`body` 저장 → `updated_at` 갱신 → 실패 로그" 루틴이 거의 동일하게 두 번 작성되어 있습니다. 에러 케이스의 `match` depth도 깊습니다.
- **개선 방향**:
  - `save_callback_response(db, request_id, status_code, body)` 헬퍼 하나로 추출하고, 성공/실패는 이 헬퍼에 다른 인자만 넘기도록 단순화합니다.
  - 콜백 발송·결과 저장을 별도 함수(`process_callback`)로 분리해 `report_sms_result` 본문이 "상태 업데이트 + (옵션) 콜백" 정도로 읽히게 합니다.

### 6. 유효 발신 카운트 로직 중복 (Rocket)

- **위치**:
  - `sms_transmitter_rocket/src/entity/phone_number.rs` `effective_daily_sent_count`/`effective_monthly_sent_count` (40~58행)
  - `sms_transmitter_rocket/src/graphql/phone_number.rs` `From<phone_number::Model>` (24~40행)
- **문제**:
  - `graphql/phone_number.rs`의 `From` impl이 "오늘보다 이전이면 0" 리셋 로직을 다시 손으로 구현하고 있습니다. `Model`에는 이미 같은 판단 로직(`effective_*_sent_count`)이 있는데, `From`에서는 이를 재사용하지 않고 중복 계산합니다.
  - 두 곳이 각자 `Local::now().date_naive()`를 호출해 "오늘"을 구하고 있어 행 단위로 시각이 미세하게 어긋날 수 있습니다(비결정적).
- **개선 방향**:
  - `Model`에 `effective_daily_sent_count(today)` / `effective_monthly_sent_count(today)` 를 `pub`으로 노출하고, `From`에서는 `today`를 한 번만 구한 뒤 이를 넘겨 재사용합니다.
  - 나아가 `From` 대신 생성자(또는 `Model::to_dto(today)`)로 바꿔 "오늘" 주입을 명시화하면 테스트도 쉬워집니다(2번과 연계).

---

## P1 — 중간 우선순위

### 7. GraphQL 호출 보일러플레이트 공통화 (Android)

- **위치**: `MainActivity` / `SettingsActivity` / `FirebaseMessageReceiverService` 전반
- **문제**:
  - `.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe({...}, {...})` + 스케줄러/디스포저 관리 패턴이 반복됩니다.
  - 에러 시 토스트(`R.string.server_connection_failed`)를 띄우는 파편화된 처리도 여러 곳에 흩어져 있습니다.
- **개선 방향**:
  - 네트워크 호출 전용 확장 함수(`Single<T>.executeNetwork(onSuccess, onError)`)나 공용 `Disposable` 관리 헬퍼를 도입합니다.
  - 더 근본적으로는 RxJava3를 **Kotlin Coroutines + `suspendCancellableCoroutine`(또는 Retrofit suspend 지원)**로 전환하는 것도 고려할 만합니다. `FirebaseMessaging.getInstance().token`도 `await()`로 깔끔하게 합류시킬 수 있습니다.

### 8. 하드코딩된 한국어 문자열 → strings.xml (Android)

- **위치**: `SmsHistoryAdapter.kt` `bind` (45~62행)
- **문제**:
  - `"시간: ..."`, `"수신번호: ..."`, `"메시지: ..."`, `"SMS: $smsStatus"` 가 코드에 하드코딩되어 있습니다. 나머지 문자열은 대부분 `strings.xml`에 있는데 이 파일만 예외로, i18n이 깨져 있습니다.
- **개선 방향**:
  - 전부 `strings.xml` 리소스로 옮기고 `getString(R.string...., args)` 포맷을 사용합니다. 기존 `sms_success`/`sms_failure` 리소스도 이미 있으므로 `"SMS: "` 접두어 문구만 리소스로 추가하면 됩니다.

### 9. GraphQL 응답 타입 안전화 (Android)

- **위치**: `GraphQlClient.kt` `findFcmTokenId`(103~125행), `GraphQlApiService.kt` `GraphQlResponse`
- **문제**:
  - `GraphQlResponse.data`가 `Map<String, Any?>?`이라서 응답을 `as? List<*>` / `as? Map<*,*>` 로 수동 캐스팅하고 있습니다. 필드명 오타나 타입 불일치가 런타임에서야 드러납니다.
- **개선 방향**:
  - 쿼리별 응답 DTO를 정의하고 Gson(또는 kotlinx.serialization)으로 매핑합니다.
  - 예: `data class FcmTokensResponse(val fcmTokens: List<FcmTokenDto>)`, `FcmTokenDto(val id: String, val token: String)`.
  - `registerFcmToken`이 `{ id }`를 반환하는데 현재 버리고 있는 것도 DTO로 받아 저장하면 P0-3의 token id 재사용과 연결됩니다.

### 10. `status` 문자열 타이핑 개선 (Rocket)

- **위치**: `sms_transmitter_rocket/src/entity/sms_request.rs` (`status: String`), `graphql/sms_request.rs` (`STATUS_*` 상수 + `SmsStatus::from_str`)
- **문제**:
  - 상태가 DB에 매직 문자열(`"Sending"/"Sent"/"Failed"`)로 저장되고, `from_str`은 **알 수 없는 값을 조용히 `Failed`로 매핑**합니다. DB에 잘못된 값이 들어가도 오류 없이 "Failed"로 보여 데이터 불일치를 숨깁니다.
- **개선 방향**:
  - SeaORM에서 enum 컬럼(예: `SmsStatus` derive `EnumIter` + `DeriveActiveEnum`)으로 전환하거나, 최소한 `from_str`이 알 수 없는 값을 로그+에러로 처리하게 바꿉니다.
  - GraphQL DTO 변환 시에도 `TryFrom`/`Result`로 실패를 명시하도록 바꾸면 silent fallback을 제거할 수 있습니다.

### 11. 설정 마이그레이션 잔재 정리 (Android)

- **위치**: `SmsLimitSettings.kt` — `readLegacyPhoneNumbers`(171~177), `readLegacyLimit`(179~183), `KEY_DAILY_LIMIT`/`KEY_MONTHLY_LIMIT`/`KEY_PHONE_NUMBERS` legacy 키
- **문제**:
  - 과거 단일 발신번호+단일 한도 구조의 legacy 키를 여전히 읽고, `savePhoneNumberConfigs`가 새 JSON(`KEY_PHONE_NUMBER_CONFIGS`)과 legacy(`KEY_PHONE_NUMBERS`)를 **둘 다** 기록합니다. 신규 JSON이 유일 소스가 된 지 오래면 legacy 경로는 데드코드에 가깝습니다.
- **개선 방향**:
  - legacy 읽기·쓰기 경로를 제거하고 `remove(...)`로 한 번 정리하는 절차를 문서화한 뒤, `getConfig`/`savePhoneNumberConfigs`를 신규 JSON 단일 소스로 단순화합니다.

### 12. 피드백 응답시 발신 카운트 증가 분기 정리 (Rocket)

- **위치**: `sms_transmitter_rocket/src/graphql/sms_request.rs` `report_sms_result` (230~254행)
- **문제**:
  - `assigned_phone_number_id`로 증가하는 정상 경로와, 그것이 없을 때 `assigned_token_id`로 fallback 발신자를 찾아 증가하는 예외 경로가 따로 작성되어 있어 읽기 어렵습니다.
- **개선 방향**:
  - "증가 대상 `phone_number::Model` 해석"을 하나의 헬퍼 함수로 추출하고 두 경로를 합칩니다. fallback은 주석으로 왜 필요한지 명시합니다(스키마 이행기 데이터 호환).

### 13. `SettingsActivity` 분리 (Android)

- **위치**: `SettingsActivity.kt` (381행)
- **문제**:
  - 액티비티 하나가 (1) 번호별 한도 UI를 프로그래매틱하게 생성·바인딩하고 (2) 입력 검증을 하고 (3) 오버레이 토글·권한 흐름을 관리하고 (4) 서버 등록까지 수행합니다. 한 파일이 네 가지 책임을 지니고 있습니다.
- **개선 방향**:
  - 번호별 한도 입력 UI 생성 로직(`createPhoneNumberLimitRow` 등)과 검증/파싱 로직(`parseLimitInput`, `collectPhoneNumberConfigs`)을 별도 클래스나 위젯으로 추출합니다.
  - 오버레이 권한/토글 상태 머신(`OverlayEnablementStatus` 관련)도 별도 헬퍼로 분리하면 액티비티는 조립 역할만 남습니다.

---

## P2 — 낮은 우선순위

### 14. Retrofit 인스턴스 무한 캐시 (Android)

- **위치**: `RetrofitClientProvider.kt` (9~21행)
- **문제**: `cache: mutableMapOf<String, Retrofit>`이 baseUrl 변경 시 계속 누적되어, URL을 자주 바꾸면 인스턴스가 무한히 쌓입니다(현재 규모에서는 미미하지만 원칙상 위험).
- **개선 방향**: 고정 크기 LRU, 또는 실행 중 서버 URL 변경이 드물다는 전제 하에 마지막 1개만 유지하도록 단순화합니다.

### 15. `notifyDataSetChanged()` → `DiffUtil` (Android)

- **위치**: `SmsHistoryAdapter.kt` `submitList` (19~22행)
- **문제**: 목록 갱신 시 전체 리바인딩을 유발합니다.
- **개선 방향**: `ListAdapter` + `DiffUtil.ItemCallback`로 교체합니다(갱신이 잦지는 않지만 관례상 개선 여지).

### 16. `sendTextMessage` 전송 결과 확인 부재 (Android)

- **위치**: `FirebaseMessageReceiverService.kt` `sendSmsSafely` (91~105행)
- **문제**: `sendTextMessage`/`sendMultipartTextMessage`를 PendingIntent(sent/delivered) 없이 호출해 "라디오에 전달됨"을 곧 "성공"으로 처리합니다. 실제 수신·실패 여부가 반영되지 않아 서버에 잘못된 `success=true`를 보고할 수 있습니다.
- **개선 방향**: sent/delivered PendingIntent를 전달해 결과를 비동기로 수신하고, 그 결과를 기준으로 `reportSmsResult`를 호출하도록 변경합니다(정확성 개선이므로 사실상 P0에 가까운 논의 대상).

### 17. 토큰 뮤텍스 홀딩 & HTTP 타임아웃 부재 (Rocket)

- **위치**: `sms_transmitter_rocket/src/firebase.rs`
- **문제**:
  - `get_access_token`이 OAuth 토큰 갱신 HTTP 호출 **전 구간 동안 뮤텍스를 들고 있어** 동시 요청이 직렬화됩니다.
  - `send_sms_request`의 FCM HTTP 호출과 토큰 발급 호출에 명시적 타임아웃이 없습니다(콜백 호출은 `Duration::from_secs(5)`가 있으나 FCM은 없음).
- **개선 방향**: 토큰 갱신은 뮤텍스 밖에서 수행하고 캐시 교체만 원자적으로 처리하거나, `tokio::sync::OnceCell`/`RwLock`으로 전환합니다. FCM 발송에도 타임아웃·재시도/백오프를 추가합니다.

### 18. 빌드타임 로컬 IP 자동 감지 (Android)

- **위치**: `sms_transmitter_Android/app/build.gradle.kts` `getEn0IpAddress` (15~26행)
- **문제**: 빌드 시점에 macOS `en0` 인터페이스 IP를 감지해 `BASE_URL`로 박아 넣는 로직이 빌드 스크립트와 개발 머신을 결합시킵니다. 다른 환경/CI 빌드에서는 의도와 다르게 동작할 수 있습니다(문서에 환경변수 우선이라고는 되어 있음).
- **개선 방향**: 기본값을 명시적 placeholder/환경변수 필수로 바꾸고, "자동 감지"는 별도 dev 전용 설정으로 분리합니다.

### 19. 부가 정리 (Rocket)

- `db.rs`는 7줄짜리 단일 함수 래퍼라 `main.rs`에 인라인하거나 그대로 두되 `config`처럼 용도를 명확히 하는 수준으로 정리 가능.
- `ACTIVE_TOKEN_SECS = 7_776_000`(90일) 같은 매직 상수는 `chrono::Duration::days(90)`로 표현해 의도를 명시.
- `fcm_token.rs`의 `register_fcm_token`(138행)과 Android `PhoneNumberResolver.normalizePhoneNumber`는 서로 다른 "전화번호 정규화" 구현이라, 규칙(트림·공백/하이픈 제거 등)이 벌어지지 않도록 양쪽 정규화 규칙을 문서화·공유할 필요(**cross-cutting**).

---

## 제안 실행 순서

1. **Rocket 안정성부터** (P0-1 패닉 제거 → P0-2 테스트 → P0-5/6 중복 제거): 변경 영향이 서버 전체에 미치므로 우선.
2. **Android 공통화** (P0-3, P0-4): FCM 등록과 사용량 집계를 각각 단일 컴포넌트로 추출. 이후 P1-7, P1-9와 자연스럽게 연결.
3. **타입/문자열 정리** (P1-8, P1-9, P1-10, P1-11): 하드코딩·매직 문자열·legacy 잔재 제거.
4. **마이너 폴리시** (P2): DiffUtil, Retry/타임아웃, 빌드 설정 등 후순위로.

> 참고: P0-16(`sendTextMessage` 결과 미확인)은 "리팩토링"이라기보다 정확성 이슈이지만, SMS 발송 성공 판정을 바꾸는 일이라 단독으로 다루는 편이 안전합니다.