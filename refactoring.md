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
| P1 | ✅ `SettingsActivity` 분리 (완료) | Android | 폼·파싱·오버레이·서버 등록 책임 분리 |
| P2 | ✅ Retrofit 인스턴스 무한 캐시 (완료) | Android | 최대 4개 LRU 캐시로 제한 |
| P2 | `notifyDataSetChanged()` → DiffUtil | Android | RecyclerView 성능 |
| P2 | ✅ `sendTextMessage` 전송 결과 확인 부재 (완료) | Android | sent PendingIntent 결과 기준으로 보고 |
| P2 | ✅ 토큰 뮤텍스 홀딩 / HTTP 타임아웃 부재 (완료) | Rocket | `firebase.rs` |
| P2 | 빌드타임 로컬 IP 감지 | Android | `build.gradle.kts` |
| P2 | 부가 정리 | Rocket | `db.rs`·매직 상수·정규화 공유 |
| P1 | ✅ 결과 보고 멱등성 부재 (완료) | Rocket+Android | 기기 이력 기준 카운트 동기화, 첫 보고만 상태 전이, 중복 수신 차단 |

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

### 7. `SettingsActivity` 분리 (Android) — ✅ 완료

- **위치**: `sms_transmitter_Android/.../SettingsActivity.kt` (362행 → 100행)
- **기존 문제**: 액티비티 하나가 (1) 번호별 한도 UI 생성·바인딩 (2) 입력 검증·파싱 (3) 오버레이 토글·권한 흐름 (4) 서버 등록·이전 서버 토큰 삭제를 모두 담당했습니다.
- **적용 내용**:
  - **(2) `SettingsInputParser`**: 전화번호 목록·한도·서버 URL 파싱과 한도 검증을 View 의존 없는 순수 객체로 추출했습니다. 한도 파싱의 `EMPTY_LIMIT(-1)`/`INVALID_LIMIT(-2)` 매직 넘버를 `LimitInput`(`Empty`/`Invalid`/`Value`) sealed 타입으로 바꿨습니다.
  - **(1) `PhoneNumberLimitForm`**: 번호별 입력 행 생성(`createRow`), 번호 목록 변경 시 입력 중인 값 보존(`sync`), 입력값 수집(`collect`)을 컨테이너 `LinearLayout`을 감싸는 클래스로 옮겼습니다.
  - **(3) `OverlayToggleController`**: 스위치 리스너, 바인딩 중 리스너 억제 플래그, 권한 요청 런처와 대기 상태, `UsageOverlayService` 시작/중지를 한곳에 모았습니다. 활성화 판단은 순수 함수 `OverlayEnablementStatus.evaluate(configs, hasPermission)`로 분리했습니다. 런처 등록 때문에 `onCreate`(STARTED 이전)에서 생성해야 하며 KDoc에 명시했습니다.
  - **(4) `FcmTokenRegistrar.registerAfterServerChange`**: 서버 URL이 바뀌었으면 이전 서버에서 토큰을 삭제한 뒤 재등록하는 흐름을 기존 등록 공통 클래스로 옮겼습니다. 삭제 실패를 무시하는 동작은 유지했습니다.
  - 액티비티에는 뷰 조회, 컴포넌트 조립, 저장 순서 조율만 남았습니다.
- **호환성**: 사용자에게 보이는 동작은 동일합니다. 저장 시 오버레이 상태 확인과 토스트가 설정 저장 **뒤**로 옮겨졌지만, 판단에 쓰는 값(입력된 config, 권한)은 저장 전후로 같습니다. 서버 URL은 기존과 같이 공백이거나 `http(s)://host[:port]` 형식이 아니면 저장된 URL을 지웁니다.
- **검증**: 순수 로직 단위 테스트 10개를 추가했습니다 — `SettingsInputParserTest` 7개(구분자·정규화·중복 제거, 한도 Empty/Value/Invalid·Int 오버플로, 하나라도 잘못되면 전체 거부, URL 끝 슬래시 제거·잘못된 형식 거부), `OverlayEnablementStatusTest` 3개(한도 필수, 한도를 권한보다 먼저 확인, 활성화 조건). `./gradlew :app:compileDebugKotlin`, `./gradlew :app:testDebugUnitTest` 통과(전체 34개). `PhoneNumberLimitForm`·`OverlayToggleController`는 View·권한에 의존해 단위 테스트하지 않았고, 실기기에서 설정 화면을 확인하지는 않았습니다.
- **후속 확인 필요(기존 동작, 이번 범위 밖)**: 저장 직후 `finish()` → `onDestroy`에서 `disposables.clear()`가 호출되므로, FCM 토큰 조회가 그 전에 끝나면 진행 중인 서버 등록·삭제 요청이 취소될 수 있습니다. 액티비티 생명주기와 무관한 범위(예: 애플리케이션 스코프)에서 실행하는 방안을 1번(보일러플레이트 공통화)과 함께 검토할 만합니다.

---

## P2 — 낮은 우선순위

### 8. Retrofit 인스턴스 무한 캐시 (Android) — ✅ 완료

- **위치**: `sms_transmitter_Android/.../RetrofitClientProvider.kt`
- **기존 문제**: `cache: mutableMapOf<String, Retrofit>`가 baseUrl마다 인스턴스를 쌓기만 하고 비우지 않았습니다.
- **적용 내용**: 접근 순서(`accessOrder = true`) `LinkedHashMap` + `removeEldestEntry`로 **최대 4개 LRU**로 제한했습니다. 기존 `@Synchronized`가 접근 순서 갱신까지 보호합니다. `OkHttpClient`는 기존처럼 하나를 공유하므로, 제거되는 것은 가벼운 Retrofit 래퍼뿐이고 커넥션 풀은 유지됩니다.
- **"마지막 1개만 유지"를 택하지 않은 이유**: 설정에서 서버 URL을 바꾸면 이전 서버에서 토큰 삭제, 새 서버에 등록이 연달아 실행됩니다(`FcmTokenRegistrar.registerAfterServerChange`). 1개만 유지하면 이 흐름에서 매번 인스턴스를 다시 만들게 되므로 작은 여유를 두었습니다.
- **검증**: `RetrofitClientProviderTest` 2개(같은 URL 재사용, 한도 초과 시 가장 오래 쓰지 않은 항목 제거) 추가. 테스트용으로 `cachedBaseUrls()`/`clearCache()`를 `@VisibleForTesting internal`로 노출했습니다. `./gradlew :app:testDebugUnitTest` 통과(전체 39개).

### 9. `notifyDataSetChanged()` → `DiffUtil` (Android)

- **위치**: `SmsHistoryAdapter.kt` `submitList`
- **문제**: 목록 갱신 시 전체 리바인딩을 유발합니다.
- **개선 방향**: `ListAdapter` + `DiffUtil.ItemCallback`로 교체합니다(갱신이 잦지는 않지만 관례상 개선 여지).

### 10. `sendTextMessage` 전송 결과 확인 부재 (Android) — ✅ 완료

- **위치**: `FirebaseMessageReceiverService.kt`, `SmsSentReceiver.kt`(신규), `SmsSendResult.kt`(신규), `SmsResultReporter.kt`(신규), `SmsHistoryDatabase.kt`, `SmsHistoryAdapter.kt`, `AndroidManifest.xml`
- **기존 문제**: `sendTextMessage`/`sendMultipartTextMessage`를 PendingIntent 없이 호출해, 예외 없이 라디오에 넘기기만 하면 즉시 `success=true`로 이력을 남기고 서버에 보고했습니다. 무선 꺼짐·서비스 없음·SMSC 거부 같은 실제 전송 실패가 성공으로 집계됐습니다.
- **성공 기준**: **sent 결과(`RESULT_OK` = SMSC 접수)**를 성공으로 봅니다. delivered(수신 단말 도달) 리포트는 사용하지 않았습니다. 통신사·단말에 따라 오지 않는 경우가 있어 이를 기다리면 서버 상태가 `Sending`에 영구히 남을 수 있기 때문입니다.
- **적용 내용**:
  - 발송 전 이력 행을 `pending_parts = 분할 개수`로 먼저 기록하고, 파트마다 `SmsSentReceiver`로 가는 sent PendingIntent를 전달합니다. PendingIntent는 extras를 동일성 비교에 쓰지 않으므로 파트별 data URI로 구분하고, `FLAG_IMMUTABLE | FLAG_ONE_SHOT`을 사용합니다.
  - `SmsSentReceiver`(manifest 등록, `exported=false`)는 FCM 서비스가 종료된 뒤에도 결과를 받습니다. `SmsHistoryDatabase.recordSentPartResult`가 트랜잭션 안에서 남은 파트를 줄이고 첫 오류를 보존하며, 마지막 파트에서만 최종 결과를 돌려줍니다. 이미 확정된 행에 대한 중복 콜백은 무시합니다. 최종 결과가 나오면 `goAsync()`로 서버 보고가 끝날 때까지 브로드캐스트를 유지합니다.
  - 결과 코드는 `SmsSendResultCodes`가 원인 문자열로 바꿉니다(알려진 코드는 이름, 그 외는 코드값). `FLAG_IMMUTABLE`이라 시스템의 `errorCode` extra는 받지 않고 결과 코드만 사용합니다.
  - 사전 조건 실패(수신번호 없음·권한 없음·한도 도달)와 라디오 전달 단계의 예외는 기존처럼 즉시 실패로 기록·보고합니다. 이력 행을 만든 뒤 예외가 나면 `markSmsFailed`로 확정합니다.
  - 서버 보고 로직을 `SmsResultReporter`로 옮겨 서비스와 리시버가 공유합니다.
  - DB 버전 3 → 4, `pending_parts INTEGER NOT NULL DEFAULT 0` 컬럼 추가(기존 행은 0 = 확정). 컬럼 추가 로직을 `addColumnIfNeeded`로 일반화했습니다.
  - 한도 집계(`countSentOrPendingSmsBetweenForSender`, 기존 `countSuccessfulSmsBetweenForSender`)는 성공 행과 **전송 중 행**을 함께 셉니다. 결과 대기 중에 연속 요청이 들어와도 한도를 넘지 않도록 보수적으로 잡았습니다. 이력 목록에는 "전송 중"(주황)으로 표시합니다.
- **호환성**: 서버 API·스키마 변경은 없습니다. 서버의 `Sending` → `Sent`/`Failed` 전이 시점이 라디오 전달 직후에서 SMSC 접수 결과 수신 시점으로 늦어집니다(보통 수 초).
- **검증**: `SmsSendResultCodesTest` 3개 추가. `./gradlew :app:compileDebugKotlin`, `./gradlew :app:testDebugUnitTest`(전체 37개), `./gradlew :app:lintDebug` 통과(신규 경고 없음). 파트 집계·리시버·DB 마이그레이션은 `Context`/SQLite에 의존해 단위 테스트하지 않았고, **실기기 발송 테스트(단일/멀티파트, 비행기 모드 등 실패 경로)와 DB 3 → 4 업그레이드는 확인하지 않았습니다.**
- **후속 확인 필요**:
  - sent 콜백이 끝내 오지 않으면(예: 발송 직후 재부팅) 행이 "전송 중"으로 남아 해당 기간 한도에 계속 포함되고, 서버도 `Sending`에 남습니다. 일정 시간이 지난 전송 중 행을 실패로 확정·보고하는 정리 작업을 검토할 만합니다.
  - 즉시 실패 보고는 여전히 서비스의 `disposables`에 묶여 있어 서비스 `onDestroy`의 `clear()`로 취소될 수 있습니다(7번 후속 항목과 같은 문제). 1번 작업 때 함께 다루는 것이 좋습니다.

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

### 14. 결과 보고 멱등성 부재 (Rocket + Android) — P1 — ✅ 완료

- **위치**: Rocket `src/entity/phone_number.rs`, `src/graphql/{sms_request,fcm_token,phone_number,mod}.rs` / Android `FirebaseMessageReceiverService.kt`, `SmsHistoryDatabase.kt`, `SmsUsageCalculator.kt`, `GraphQlClient.kt`, `SmsResultReporter.kt`, `SmsSentReceiver.kt`, `FcmTokenRegistrar.kt`
- **기존 문제**:
  - `report_sms_result`가 현재 상태를 보지 않고 카운트 증가·상태 덮어쓰기·콜백 호출을 수행해, 같은 `requestId`가 두 번 보고되면 모두 반복됐습니다.
  - Android는 `requestId` 기준 중복 제거가 없어 FCM 중복 전달 시 SMS가 두 번 발송됐습니다.
  - `register_fcm_token`은 번호가 다른 토큰으로 옮겨가면 카운트를 0으로 초기화했고, 다른 서버를 통해 보낸 문자는 반영할 방법이 없었습니다.
- **작업 중 발견한 기존 버그**: `report_sms_result`의 카운트 증가가 **DB에 저장되지 않고 있었습니다.** `Model`을 수정한 뒤 `.into()`로 만든 `ActiveModel`은 모든 컬럼이 `Unchanged`라서 `update`가 no-op이 됩니다(SeaORM 1.1.20의 `From<Model>` 구현과 `is_noop()` 분기로 확인). 서버의 발신 카운트는 계속 0이었고, 서버 측 한도 판단(`can_send_now`)과 발신 번호 선택(`availability_score`)이 카운트 없이 동작했습니다. 이 동작을 고정하는 테스트(`plain_into_does_not_persist_count_changes`)를 남기고, 카운트 컬럼을 `Set`으로 표시하는 `into_sent_counts_active_model()`을 추가했습니다.
- **적용 방향**: 실제 발송 기록은 모두 기기에 있으므로 서버 카운트를 "보고마다 +1"이 아니라 **기기 이력 기준 동기화**로 바꿨습니다.
- **적용 내용 (서버)**:
  - `SentCountsInput { countedOn: NaiveDate, dailySentCount, monthlySentCount }`를 추가하고 `ReportSmsResultInput.sentCounts`, `PhoneNumberInput.sentCounts`(번호별)로 받습니다. 둘 다 optional입니다.
  - `Model::merge_reported_sent_counts`: `countedOn`이 서버의 오늘이면 일간, 같은 달이면 월간 카운트를 `max(서버 유효값, 보고값)`으로 반영합니다. 보고 순서가 뒤바뀌거나 앱 재설치로 이력이 비어도 기간 안에서는 카운트가 줄지 않습니다. 다른 기간의 값(기기 시계·시간대가 자정 근처에서 어긋난 경우)은 무시하고 다음 보고·등록에서 따라잡습니다. 음수는 0으로 처리합니다.
  - `report_sms_result`: `sentCounts`가 있으면 상태와 무관하게 동기화합니다(같은 값 재반영이라 안전). 없으면(구버전 앱) **첫 성공 보고에서만** 증가합니다. 상태 전이와 콜백은 `status == Sending`인 첫 보고에서만 수행하고, 이후 보고는 카운트 동기화만 한 뒤 `true`로 응답합니다. 할당된 번호 행이 사라진 경우는 mutation 전체를 실패시키지 않고 warn 로그만 남깁니다(실패시키면 상태가 `Sending`에 영구히 남음).
  - `register_fcm_token`: 유지되는 번호는 보고값을 병합하고, 새 번호·다른 토큰에서 옮겨온 번호는 0에서 시작해 보고값을 반영합니다.
- **적용 내용 (Android)**:
  - 이력 DB v5: `request_id` 컬럼과 인덱스를 추가했습니다. `onMessageReceived`에서 같은 `requestId`의 이력이 이미 있으면 발송하지 않고 건너뜁니다. 처리 기록을 이력에 영구 보관하므로 별도 TTL 저장소는 두지 않았습니다.
  - `SmsUsageCalculator.confirmedSentCounts`: 서버에는 **확정된 성공 건수만** 보냅니다. 로컬 한도 판단과 사용량 표시는 기존처럼 전송 중인 건을 포함합니다(P2-10). 전송 중인 건은 나중에 실패할 수 있는데, 서버는 기간 안에서 카운트를 줄이지 않기 때문입니다. `countedOn`은 기기 기본 시간대의 날짜입니다.
  - 결과 보고(`SmsResultReporter`: 즉시 실패와 `SmsSentReceiver` 최종 결과 모두)와 토큰 등록(`FcmTokenRegistrar.registerToken`)에 발신 번호별 확정 건수를 실어 보냅니다. 리시버는 PendingIntent extra로 발신 번호를 전달받습니다.
- **호환성·배포 순서**: **서버를 먼저 배포해야 합니다.** 새 앱이 구버전 서버에 `sentCounts`를 보내면 알 수 없는 입력 필드라 GraphQL 오류가 나서 보고·등록이 실패합니다. 반대로 구버전 앱은 새 서버에서 기존처럼 동작합니다(첫 성공 보고에서만 +1). 그동안 저장되지 않던 서버 카운트는 배포 후 첫 등록·보고 때 기기 값으로 채워집니다. DB 마이그레이션은 서버에 없고, Android는 이력 DB가 4 → 5로 올라갑니다.
- **남은 한계**:
  - 서버가 FCM 전송 실패(502/504 등 응답 유실 포함)로 `Failed` 처리한 뒤 기기가 실제로 보내고 성공을 보고하면, 상태는 `Failed`로 남고 카운트만 동기화됩니다.
  - 같은 번호의 SIM을 다른 기기로 옮기면, 이전 기기의 이력은 새 기기 카운트에 포함되지 않습니다. 서버 값은 `max` 반영이라 같은 기간 동안은 유지됩니다.
  - 서버의 `request_sms`는 발송 시점에 카운트를 예약하지 않으므로, 결과 보고 전에 몰린 요청은 같은 번호에 배정될 수 있습니다(기존과 동일). 기기의 로컬 한도 판단이 최종 방어선입니다.
- **검증**:
  - Rocket: 엔티티 테스트 7개(병합: 큰 값 반영·감소 금지·지난 기간 대체·날짜만 다른 같은 달·다른 달 무시, `into()` no-op 고정, 카운트 저장 헬퍼)와 resolver 테스트 5개(마이그레이션한 메모리 SQLite에 실제 GraphQL 실행: 등록 동기화·감소 금지, 번호 이동 시 보고값 유지, 반복 보고 시 첫 상태 유지와 카운트 동기화, 구버전 보고 1회만 증가·저장, 실패 보고 미증가)를 추가했습니다. `cargo fmt`, `cargo clippy --all-targets`(기존 경고 2건 외 없음), `cargo test` 통과(전체 43개).
  - Android: `GraphQlClientTest`에 2개(등록 시 번호별 `sentCounts` 직렬화, 보고 시 `sentCounts` 유무)를 추가했습니다. `./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest`(전체 41개), `:app:lintDebug`(신규 경고 없음) 통과. 중복 수신 차단과 이력 DB 4 → 5 업그레이드는 `Context`/SQLite에 의존해 단위 테스트하지 않았고, **실기기·실서버 연동은 확인하지 않았습니다.**

---

## 제안 실행 순서

1. **Rocket 마무리**: `status` enum 전환(4)·피드백 발신 카운트 분기 정리(6) 모두 완료. 남은 Rocket 항목은 P2(11, 13).
2. **Android 구조 정리**: 타입 안전화(3)·`SettingsActivity` 분리(7) 완료 → GraphQL 보일러플레이트 공통화(1) → 하드코딩 문자열(2).
3. **타입/문자열/설정 정리**: 하드코딩 문자열(2) 정리 (`status`(4)·legacy 잔재(5)는 완료).
4. **마이너 폴리시**: P2 항목(8~13) — DiffUtil, Retrofit 캐시, 빌드 설정 등 후순위(타임아웃·재시도(11)는 완료).
5. **멱등성**: 결과 보고 멱등성(14) 완료. 배포는 서버를 먼저 하고 앱을 그다음에 해야 합니다.

> 참고: `sendTextMessage` 전송 결과 확인(10)은 정확성 이슈라 단독으로 처리했습니다(완료).