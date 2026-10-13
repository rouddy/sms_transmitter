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
| P1 | ✅ GraphQL 응답 타입 안전화 (완료) | Android | 연산별 응답 DTO 적용, 수동 캐스팅·silent skip 제거 |
| P1 | ✅ `status` 문자열 타이핑 (완료) | Rocket | SeaORM enum 적용, silent fallback 제거 |
| P1 | ✅ 설정 마이그레이션 잔재 정리 (완료) | Android | legacy 읽기·쓰기 제거, JSON 단일 소스 |
| P1 | ✅ 피드백 응답시 발신 카운트 증가 분기 (완료) | Rocket | fallback 경로 제거, 단일 경로로 정리 |
| P1 | `SettingsActivity` 분리 | Android | 단일 액티비티의 다중 책임 |
| P2 | Retrofit 인스턴스 무한 캐시 | Android | `RetrofitClientProvider` |
| P2 | `notifyDataSetChanged()` → DiffUtil | Android | RecyclerView 성능 |
| P2 | `sendTextMessage` 전송 결과 확인 부재 | Android | "성공" != 실제 수신 |
| P2 | ✅ 토큰 뮤텍스 홀딩 / HTTP 타임아웃 부재 (완료) | Rocket | `firebase.rs` |
| P2 | 빌드타임 로컬 IP 감지 | Android | `build.gradle.kts` |
| P2 | 부가 정리 | Rocket | `db.rs`·매직 상수·정규화 공유 |
| P1 | 결과 보고 멱등성 부재 | Rocket+Android | 같은 `requestId` 중복 보고시 카운트 중복 증가 |

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

### 3. GraphQL 응답 타입 안전화 (Android) — ✅ 완료

- **위치**: `GraphQlApiService.kt`, `GraphQlClient.kt`, `GraphQlClientTest.kt`
- **기존 문제**: 응답을 `Map<String, Any?>`와 수동 캐스팅으로 처리하고, 잘못된 토큰 항목이나 누락된 데이터를 토큰 없음으로 취급했습니다.
- **적용 내용**:
  - `GraphQlResponse<T>`와 `GraphQlError`, 연산별 응답 DTO를 정의하고 Retrofit 메서드를 조회·등록·삭제·결과 보고의 구체적인 반환 타입으로 분리했습니다. 기존 Gson 변환기를 사용합니다.
  - `findFcmTokenId`는 DTO 필드로 조회하며, 목록 내 필수 ID/토큰이 누락되거나 비어 있는 항목은 조용히 건너뛰지 않고 오류로 처리합니다.
  - 공통 `requireData()`로 GraphQL 오류 및 누락된 data를 검증하고, 각 연산에서 등록 ID와 Boolean 응답 필드의 존재를 확인합니다. Gson으로 읽는 필드는 nullable로 선언하고 클라이언트에서 명시적으로 검증합니다.
- **호환성**: 공개 `Single<Unit>` API, 유효한 목록에 일치하는 토큰이 없으면 삭제 없이 성공하는 동작, 오류 없는 Boolean `false` 응답을 허용하는 동작을 유지했습니다. 앱의 네트워크 스케줄링은 변경하지 않았습니다.
- **후속 개선**: 등록 응답 ID는 검증하지만 저장하지 않습니다. 서버 URL별 ID 캐시와 무효화 정책은 이번 범위에 포함하지 않았으며, 삭제 시 전체 토큰 목록을 조회하는 구조는 유지합니다.
- **검증**: 실제 Retrofit/Gson 파이프라인에 인터셉터로 응답을 주입하는 회귀 테스트 14개를 추가했습니다. 정상 조회·삭제, 빈/불일치 목록, 누락 데이터·잘못된 응답 구조, GraphQL 오류, mutation 응답 검증 및 전송 오류 전파를 확인했습니다. `./gradlew :app:compileDebugKotlin`과 `./gradlew :app:testDebugUnitTest` 통과(기존 7개 포함 전체 21개). 테스트에만 동기 RxJava 어댑터를 사용하며 실제 서버/기기 연동 테스트는 수행하지 않았습니다.

### 4. `status` 문자열 타이핑 개선 (Rocket) — ✅ 완료

- **위치**: `sms_transmitter_rocket/src/entity/sms_request.rs`, `src/graphql/sms_request.rs`
- **기존 문제**: 상태를 문자열로 처리하고 알 수 없는 값을 조용히 `Failed`로 매핑해 데이터 불일치를 숨겼습니다.
- **적용 내용**:
  - 엔티티에 `EnumIter` + `DeriveActiveEnum` 기반 `SmsStatus`를 정의하고 `Model.status`를 `String`에서 enum으로 변경했습니다.
  - 요청 생성·FCM 실패·결과 보고의 상태 저장에 enum을 사용하고 `STATUS_*` 상수와 `from_str` fallback을 제거했습니다.
  - GraphQL enum은 기존 API를 유지하며, 엔티티 enum에서 모든 variant를 명시적으로 매핑합니다. 알 수 없는 DB 값은 SeaORM 역직렬화 단계에서 오류가 발생하고 조회 resolver의 `?`를 통해 GraphQL 오류로 전달되므로 DTO 변환은 `From`으로 충분합니다.
- **호환성**: SQLite 문자열 컬럼과 기존 저장값(`Sending`/`Sent`/`Failed`), GraphQL 값(`SENDING`/`SENT`/`FAILED`)을 유지하므로 DB 마이그레이션은 필요 없습니다. DB 자체에 CHECK 제약을 추가한 것은 아니므로 외부 SQL로 잘못된 값을 넣으면 조회 시 오류가 납니다.
- **검증**: DB 값 매핑, SQLite의 정상값 역직렬화·알 수 없는 값 거부, GraphQL enum 값 유지 테스트를 추가했습니다. `cargo check` 및 `cargo test` 통과(전체 21개).

### 5. 설정 마이그레이션 잔재 정리 (Android) — ✅ 완료

- **위치**: `sms_transmitter_Android/.../SmsLimitSettings.kt`
- **기존 문제**: 과거 단일 발신번호+단일 한도 구조의 legacy 키(`daily_limit`/`monthly_limit`/`phone_numbers`)를 신규 JSON(`phone_number_configs`)이 비어 있을 때 fallback으로 읽었고, `savePhoneNumberConfigs`가 신규 JSON과 legacy `phone_numbers`를 **둘 다** 기록했습니다.
- **적용 내용**:
  - `getConfig`는 `readPhoneNumberConfigs()`만 사용하고, `savePhoneNumberConfigs`는 JSON만 기록합니다. `readLegacyPhoneNumbers`/`readLegacyLimit`, legacy 키 상수, `PHONE_NUMBER_SEPARATOR`를 제거했습니다.
  - 정리 절차: 생성자(`init`)에서 legacy 키가 남아 있으면 한 번 `remove`합니다. 키가 없으면 `contains` 확인만 하고 쓰기는 하지 않습니다. 키 이름은 `LEGACY_KEYS`에 남기고 배경을 주석으로 기록했습니다.
- **안전성 근거(git 이력 확인)**:
  - `daily_limit`/`monthly_limit`은 `6e0fcba`(2026-04-22)부터, `phone_numbers`는 `73ff72d`(2026-08-12)부터, JSON은 바로 다음 날 `4e9e2f6`(2026-08-13)부터 기록됐습니다. 첫 출시 태그 `0.2.0`(2026-08-20)은 이미 JSON을 기록합니다.
  - legacy fallback은 `phone_numbers`가 있어야만 설정을 만들 수 있었으므로, 이 키가 없는 0.2.0 이전 빌드의 단일 한도는 0.2.0에서도 이미 복원되지 않았습니다. 따라서 출시본 기준 동작 변화는 없고, 영향 범위는 `73ff72d`~`4e9e2f6` 사이 하루 동안의 개발 빌드뿐입니다.
- **호환성**: 0.2.0 이상에서 업데이트하는 기기는 JSON을 그대로 읽습니다. 다운그레이드 시 구 버전도 JSON을 우선 읽으므로 legacy 키 삭제의 영향이 없습니다.
- **검증**: `./gradlew :app:compileDebugKotlin`, `./gradlew :app:testDebugUnitTest` 통과. `SmsLimitSettings`는 `Context`/`SharedPreferences`에 의존하고 프로젝트에 Robolectric이 없어 전용 단위 테스트는 추가하지 않았으며, 실기기 업그레이드 테스트도 수행하지 않았습니다.

### 6. 피드백 응답시 발신 카운트 증가 분기 정리 (Rocket) — ✅ 완료

- **위치**: `sms_transmitter_rocket/src/graphql/sms_request.rs` `report_sms_result`
- **기존 문제**: `assigned_phone_number_id`로 증가하는 정상 경로와, 그것이 없을 때 `assigned_token_id`로 fallback 발신자를 찾아 증가하는 예외 경로가 따로 작성되어 있어 읽기 어려웠습니다. 두 경로에 증가·저장 코드가 중복되고, "증가 대상 없음"을 정상 경로는 하드 에러로, fallback은 조용한 no-op으로 비대칭 처리했습니다.
- **적용 내용**:
  - fallback 경로를 **제거**하고 `assigned_phone_number_id` 단일 경로로 정리했습니다. `request_sms`는 요청 생성 시 `assigned_token_id`와 `assigned_phone_number_id`를 항상 함께 기록하므로, NULL인 행은 `m20260813_000004`(한도를 번호 단위로 이전) 이전에 생성돼 백필 시 NULL로 남은 요청뿐입니다. 이 배경을 코드 주석으로 남겼습니다.
  - fallback에 있던 `fcm_token` 조회를 제거했습니다. `token.id`는 인자로 받은 `token_id`와 동일해 필터 외에는 쓰이지 않는 중복 조회였고, 바로 아래 `last_access_time` 갱신 블록에서 같은 토큰을 한 번 더 조회하고 있었습니다.
  - fallback의 `.one()`은 정렬이 없어 토큰이 번호를 여러 개 보유하면 어느 번호가 증가할지 비결정적이었습니다. 경로 자체를 없애면서 이 문제도 사라졌습니다.
  - `if let` / `else if let` 중첩을 `match`로 바꾸고, 중복됐던 `now.date()` 호출과 `today` 지역 변수 혼용을 통일했습니다. 해석 실패는 `tracing::warn!`으로 남깁니다(기존에는 완전히 무음).
- **호환성**: 신규 요청의 동작은 동일합니다. 바뀌는 지점은 두 가지입니다. (1) `assigned_phone_number_id`가 NULL인 이행기 요청은 성공 보고 시 발신 카운트가 증가하지 않고 warn 로그만 남습니다(상태 갱신·콜백 호출은 정상 수행). (2) 도달 불가능했던 `"FCM token not found"` 에러가 사라졌습니다 — `phone_numbers.fcm_token_id`는 `ON DELETE CASCADE`, `sms_requests.assigned_token_id`는 `ON DELETE SET NULL`이므로 토큰이 삭제되면 `assigned_token_id`가 NULL이 되어 애초에 해당 분기에 진입할 수 없었습니다. 이 에러는 발생 시 mutation 전체를 실패시켜 `status`를 `Sending`에 영구히 남기는 최악의 실패 모드를 갖고 있었습니다.
- **후속 확인 필요**: 운영 DB에 `assigned_phone_number_id IS NULL AND status = 'Sending'`인 행이 남아 있다면, 그 요청들은 결과 보고 시 카운트가 반영되지 않습니다. 배포 전 아래 쿼리로 0인지 확인하는 것을 권장합니다.
  ```sql
  SELECT count(*) FROM sms_requests
  WHERE assigned_phone_number_id IS NULL AND status = 'Sending';
  ```
- **검증**: `cargo check`, `cargo clippy`, `cargo test` 통과(기존 21개 전체). 순수 삭제 위주 변경이고 `report_sms_result`는 GraphQL 컨텍스트에 의존하므로 전용 테스트 하네스는 추가하지 않았습니다.

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

### 11. 토큰 뮤텍스 홀딩 & HTTP 타임아웃 부재 (Rocket) — ✅ 완료

- **위치**: `sms_transmitter_rocket/src/firebase.rs`
- **기존 문제**:
  - `get_access_token`이 OAuth 토큰 갱신 HTTP 호출 전 구간 동안 뮤텍스를 들고 있어 동시 요청이 직렬화됐습니다.
  - `send_sms_request`의 FCM HTTP 호출과 토큰 발급 호출에 명시적 타임아웃이 없었습니다(콜백 호출만 `Duration::from_secs(5)` 보유).
- **적용 내용**:
  - 캐시를 `Arc<Mutex<..>>` → `RwLock`으로 바꾸고, 갱신 직렬화용 `token_refresh: Mutex<()>`를 분리했습니다. 캐시 읽기는 read 락으로 통과하고 갱신만 직렬화되며, 갱신 락 획득 후 재확인(double-check)으로 대기 중 다른 태스크가 이미 갱신했으면 HTTP 호출을 생략합니다.
  - HTTP 클라이언트에 `connect_timeout` 5초 / `timeout` 30초를 설정했습니다. 이로 인해 `FirebaseClient::new`가 `Self` → `Result<Self>`로 바뀌었습니다.
  - FCM 발송에 재시도/백오프를 추가했습니다(`send_fcm` + `fcm_retry_delay`). 최대 3회, 429는 60초 기준·500/503은 10초 기준 지수 백오프, `Retry-After` 존중(초 단위 및 HTTP 날짜), 상한 120초.
  - 토큰 응답 검증을 추가했습니다. 비성공 상태는 **응답 본문을 포함해** 실패시키고(`invalid_grant` 등 자격증명 오류 진단에 필수), 빈 토큰·과도하게 짧은 수명도 거부합니다.
  - 성공 로그가 `fcm_token` 대신 `request_id`를 남기도록 바뀌어 로그에 토큰이 노출되지 않습니다.
- **재시도 범위를 좁힌 근거**: 429/500/503만 재시도합니다. 이 코드들은 FCM이 메시지를 받지 않았음을 명시적으로 알린 경우이므로 재전송이 안전합니다. 반면 502/504나 전송 타임아웃은 중간 경로에서 응답만 유실됐을 수 있어, 재시도하면 같은 `requestId`로 SMS가 두 번 발송될 수 있습니다. 이 판단을 코드 주석과 테스트로 고정했습니다.
- **후속 검토에서 보강한 사항**:
  - **토큰 staleness**: 백오프 대기(최악 약 3분)가 토큰 만료 여유(60초)를 넘길 수 있어, 재시도 중 401로 실패할 수 있었습니다. 액세스 토큰 취득을 재시도 루프 안으로 옮겨 시도마다 유효한 토큰을 쓰도록 했습니다(캐시 히트면 비용 없음).
  - **`unreachable!()` 제거**: `for` 루프 끝의 `unreachable!("the last attempt never retries")`는 불변식이 `fcm_retry_delay`에 있어, 그 함수를 고치면 운영 중 패닉으로 이어질 수 있었습니다. `loop` + 모든 비재시도 경로에서 `bail!` 구조로 바꿔 컴파일러가 보장하도록 했습니다.
  - **`Retry-After` 파싱 실패 처리**: 기존에는 헤더를 해석하지 못하면 `None`을 반환해 **재시도를 아예 포기**했는데, 주석의 의도("더 일찍 재시도하지 않는다")와 달랐습니다. 파싱 실패는 헤더를 무시하고 계산된 백오프를 쓰도록 바꿨고, "과도한 대기(>120초)는 즉시 실패"라는 정책만 남겼습니다.
- **검증**: `fcm_retry_delay`를 순수 함수로 유지해 단위 테스트 7개를 추가했습니다 — 지수 백오프 값, 비재시도 상태(4xx/502/504), 마지막 시도, `Retry-After` 연장/단축 금지, 과도한 값 즉시 실패, 파싱 실패 폴백, HTTP 날짜(`GMT` 표기) 해석 및 과거 시각 무시. `cargo fmt --check`·`cargo check`·`cargo clippy --all-targets`·`cargo test` 통과(전체 28개). 남은 clippy 경고 2건은 이 항목과 무관한 기존 것입니다. 실제 FCM 서버 연동 테스트는 수행하지 않았습니다.
- **부수 정리**: 참조되지 않는 `src/firebase/test-key.pem`(개인키 형식)이 작업 트리에 남아 있었습니다. 실수로 커밋되지 않도록 `.gitignore`에 `*.pem`을 추가했습니다. 파일 자체의 삭제 여부는 보류했습니다.

### 12. 빌드타임 로컬 IP 자동 감지 (Android)

- **위치**: `sms_transmitter_Android/app/build.gradle.kts` `getEn0IpAddress`
- **문제**: 빌드 시점에 macOS `en0` 인터페이스 IP를 감지해 `BASE_URL`로 박아 넣는 로직이 빌드 스크립트와 개발 머신을 결합시킵니다. 다른 환경/CI 빌드에서는 의도와 다르게 동작할 수 있습니다(문서에 환경변수 우선이라고는 되어 있음).
- **개선 방향**: 기본값을 명시적 placeholder/환경변수 필수로 바꾸고, "자동 감지"는 별도 dev 전용 설정으로 분리합니다.

### 13. 부가 정리 (Rocket)

- `db.rs`는 7줄짜리 단일 함수 래퍼라 `main.rs`에 인라인하거나 그대로 두되 `config`처럼 용도를 명확히 하는 수준으로 정리 가능.
- `ACTIVE_TOKEN_SECS = 7_776_000`(90일) 같은 매직 상수는 `chrono::Duration::days(90)`로 표현해 의도를 명시.
- `fcm_token.rs`의 `register_fcm_token`과 Android `PhoneNumberResolver.normalizePhoneNumber`는 서로 다른 "전화번호 정규화" 구현이라, 규칙(트림·공백/하이픈 제거 등)이 벌어지지 않도록 양쪽 정규화 규칙을 문서화·공유할 필요(**cross-cutting**).

---

## 추가 발견 항목

### 14. 결과 보고 멱등성 부재 (Rocket + Android) — P1

- **위치**: `sms_transmitter_rocket/src/graphql/sms_request.rs` `report_sms_result`, `sms_transmitter_Android/.../FirebaseMessageReceiverService.kt` `onMessageReceived`
- **문제**:
  - `report_sms_result`는 현재 상태를 보지 않고 발신 카운트를 증가시키고 상태를 덮어씁니다. 같은 `requestId`로 두 번 보고되면 카운트가 두 번 증가해 한도 계산이 어긋납니다.
  - Android 쪽도 `requestId` 기준 중복 제거가 없어, FCM이 같은 메시지를 두 번 전달하면 SMS가 두 번 발송됩니다. FCM은 at-least-once 전달이므로 중복은 정상 동작 범위입니다.
- **개선 방향**:
  - 서버: `report_sms_result`에서 `status`가 `Sending`일 때만 카운트 증가와 상태 전이를 수행하고, 이미 종료 상태면 성공으로 응답하되 아무것도 바꾸지 않습니다(멱등). 트랜잭션 안에서 상태를 확인하므로 추가 락은 불필요합니다.
  - Android: 처리한 `requestId`를 짧은 TTL로 기록해 중복 수신을 건너뜁니다.
- **우선순위 근거**: 11번에서 FCM 재시도를 추가하면서 중복 전송 경로를 의도적으로 배제했지만(429/500/503만 재시도), FCM 자체의 중복 전달은 여전히 남아 있습니다. 카운트 정확성에 직접 영향이 있어 P1로 둡니다.

---

## 제안 실행 순서

1. **Rocket 마무리**: `status` enum 전환(4)·피드백 발신 카운트 분기 정리(6) 모두 완료. 남은 Rocket 항목은 P2(11, 13).
2. **Android 구조 정리**: 타입 안전화(3) 완료 → GraphQL 보일러플레이트 공통화(1) → 하드코딩 문자열(2).
3. **타입/문자열/설정 정리**: 하드코딩 문자열(2) 정리 (`status`(4)·legacy 잔재(5)는 완료).
4. **마이너 폴리시**: P2 항목(8~13) — DiffUtil, Retrofit 캐시, 빌드 설정 등 후순위(타임아웃·재시도(11)는 완료).
5. **멱등성**: 결과 보고 멱등성(14) — 서버 가드를 먼저 넣고 Android 중복 제거를 뒤따르게 하는 것이 안전합니다.

> 참고: `sendTextMessage` 전송 결과 확인(10)은 "리팩토링"이라기보다 정확성 이슈이므로 단독으로 다루는 편이 안전합니다.