---
phase: 02-consistency-recovery-gaps
reviewed: 2026-07-29T03:59:13Z
depth: standard
files_reviewed: 10
files_reviewed_list:
  - payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java
  - payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java
  - payment-service/src/main/java/com/example/payment/application/dto/CheckChargeResponseDto.java
  - payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java
  - payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java
  - payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java
  - payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java
  - payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java
  - payment-service/src/test/java/com/example/payment/integration/CancelRaceIdempotencyIT.java
  - payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java
findings:
  critical: 3
  warning: 5
  info: 3
  total: 11
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-07-29T03:59:13Z
**Depth:** standard
**Files Reviewed:** 10
**Status:** issues_found

## Summary

`incrementPgRetryCount`의 원자 UPDATE + 재조회 패턴(D-04) 자체는 정확하다 — `ProcessingRecoveryConcurrencyIT`의 검증(A)이 증명하듯 pg_retry_count 증가 경쟁에서 유실은 없다. 그러나 이번 리뷰에서 그보다 심각한 두 종류의 결함을 발견했다.

첫째, `CancelPaymentService.executeCancel()`은 FAILED 재시도 흐름(`handleExistingRequest`의 FAILED 분기)에서 이미 PENDING으로 UPDATE된 기존 `CancelRequest`를 재사용하지 않고 **매번 새 도메인 객체를 만들어 INSERT를 다시 시도**한다. 이는 `(payment_id, request_hash)` UK를 자기 자신과 충돌시켜 `DataIntegrityViolationException`을 유발하고, 그 예외가 "다른 파드의 레이스 패배"로 오인되어 `handleExistingRequest`로 다시 위임된 뒤 PENDING 상태 그대로 조용히 반환된다 — 즉 FAILED 재시도 요청은 risk/PG를 한 번도 다시 호출하지 못한 채 사실상 무시된다. `sysdesign/cancel-design.md`의 시퀀스 다이어그램과도 어긋난다.

둘째, 같은 메서드에서 risk 호출 실패 시 "명확한 에러(한도초과 등)"와 "타임아웃/네트워크 유실"을 구분하지 않고 **항상 compensate()를 호출**한다. `cancel-design.md`는 이 둘을 명확히 다른 분기로 문서화하고 있고("risk 명확한 에러" 분기에는 compensate 호출이 없음), 이번 스프린트에서 막 구현된 `isCharged()`가 정확히 이 구분을 위한 것으로 보이는데(`PendingRecoveryService`에서만 사용됨) `CancelPaymentService`에는 연결되어 있지 않다. 실제로 한도 초과 등으로 애초에 차감이 일어나지 않은 상황에서 compensate가 호출되면 가맹점 일일 한도 사용량을 잘못 되돌려(과대 복원) 한도 검증 무결성이 깨진다.

셋째, `ProcessingRecoveryService`의 `compensateAndFail()`은 `incrementPgRetryCount`와 달리 원자적 조건부 가드가 전혀 없다 — `CancelRequestJpaEntity`에 `@Version`도 없다. `ProcessingRecoveryScheduler`의 Redis 락은 `leaseTime=55초` 고정(워치독 미사용)이라 처리 시간이 이를 넘으면 락이 자동 해제되어 중복 처리 창이 열릴 수 있는데, 이 경우 두 스레드/파드가 동일 `CancelRequest`에 대해 `compensateAndFail()`을 동시에 실행하면 risk-management에 보상(compensate)이 두 번 호출될 수 있다. 정확히 이 D-04 패턴이 필요했던 지점인데 `pg_retry_count` 증가에만 적용되고 최종 FAILED 전이/보상 경로에는 적용되지 않았다. `ProcessingRecoveryConcurrencyIT`도 검증(A) 증가 경쟁과 검증(B) TX3(APPROVED) 경쟁만 다루고 이 FAILED/보상 경쟁 경로는 테스트되지 않는다.

이 외에 HTTP 클라이언트의 응답 검증 비일관성, CircuitBreaker 공유, 로깅 레벨 오분류 등 경계 사례를 Warning/Info로 정리했다.

## Critical Issues

### CR-01: FAILED 재시도가 자기 자신과 UK 충돌 → risk/PG 재호출 없이 조용히 무시됨

**File:** `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java:68-118`

**Issue:**
`handleExistingRequest`의 FAILED 분기는 기존 `CancelRequest`(id 존재)를 `raiseToPending()` 후 UPDATE로 저장한다(74-79행, 정확한 처리).

```java
case FAILED -> {
    cancelRequest.raiseToPending();
    cancelRequestRepository.save(cancelRequest);
    recordHistory(cancelRequest.getId(), CancelStatus.PENDING, "FAILED 재시도");
    yield executeCancel(payment, items, cancelRequest.getRequestHash(), command);
}
```

하지만 이어서 호출되는 `executeCancel(payment, items, requestHash, command)`는 **id 없는 새 `CancelRequest.create(...)` 객체**를 만들어 `cancelTxWriter.saveTx1(...)`로 INSERT를 시도한다(92-98행).

```java
CancelRequest cancelRequest = CancelRequest.create(
    payment.getId(), requestHash, cancelAmount, command.cancelReason(),
    command.cancelPaymentItemIds());
try {
    cancelRequest = cancelTxWriter.saveTx1(cancelRequest);
} catch (DataIntegrityViolationException e) {
    CancelRequest winner = cancelRequestRepository
        .findByPaymentIdAndRequestHash(payment.getId(), requestHash)
        .orElseThrow(() -> e);
    return handleExistingRequest(winner, command, payment, items);
}
```

방금 UPDATE로 PENDING 상태가 된 바로 그 행이 이미 `(payment_id, request_hash)`를 점유하고 있으므로 이 INSERT는 **항상** UK 위반으로 실패한다. 그 `DataIntegrityViolationException`은 "다른 파드의 레이스 패배"로 취급되어 `findByPaymentIdAndRequestHash`로 방금 UPDATE한 그 행을 다시 조회하고 `handleExistingRequest`를 재귀 호출한다 — 이번엔 상태가 PENDING이므로 `case COMPLETED, PENDING, PROCESSING -> cancelRequest;` 분기에 걸려 **아무 처리도 하지 않고 그대로 반환**한다.

결과: FAILED 재시도 요청은 risk 호출도, PG 호출도, TX2/TX3도 실행되지 않은 채 PENDING 상태만 응답으로 돌아온다. 실제 재처리는 5분 뒤 pending-recovery 스케줄러가 집어갈 때까지 지연되며, 동기 재시도 API를 호출한 클라이언트 입장에서는 "즉시 재시도했다"는 기대와 다르게 아무 일도 일어나지 않는다. `sysdesign/cancel-design.md`의 시퀀스(62-72행: FAILED → PENDING UPDATE 후 신규 처리와 동일하게 Step3 이후로 진행하되 기존 행을 재사용)와도 어긋난다. `CancelRaceIdempotencyIT`는 신규 요청 간 레이스만 다루고 이 FAILED 재시도 경로는 테스트되지 않는다.

**Fix:**
FAILED 재시도 시 `executeCancel`이 새 엔티티를 만들지 않고, 이미 PENDING으로 UPDATE된 기존 `cancelRequest`(id 포함)를 그대로 이어받아 risk 호출부터 재개하도록 분리해야 한다. 예:

```java
case FAILED -> {
    cancelRequest.raiseToPending();
    CancelRequest reactivated = cancelRequestRepository.save(cancelRequest);
    recordHistory(reactivated.getId(), CancelStatus.PENDING, "FAILED 재시도");
    yield proceedFromRisk(payment, items, reactivated, command); // saveTx1 재실행 없이 risk부터 진행
}
```

`executeCancel`을 "TX1 INSERT + 이후 흐름"과 "risk 호출부터 이후 흐름"으로 나누어, 신규 생성 경로만 전자를, FAILED 재시도 경로는 후자를 타도록 리팩터링할 것.

---

### CR-02: risk 호출 실패 시 "명확한 에러"와 "타임아웃/네트워크 유실"을 구분하지 않고 항상 compensate() 호출

**File:** `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java:109-118`

**Issue:**
```java
try {
    LocalDate kstDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
    riskManagementPort.validateAndReserve(
        payment.getMerchantId(), cancelRequest.getId(), cancelAmount, kstDate);
} catch (Exception e) {
    tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);
    markFailed(cancelRequest, e.getMessage());
    throw e;
}
```

`sysdesign/cancel-design.md` 97-113행은 이 지점을 두 가지 분기로 명확히 나눈다:
- "risk 명확한 에러(한도 초과 등)" → **compensate 호출 없이** 곧바로 CancelRequest FAILED UPDATE
- "risk 타임아웃/네트워크 유실"(차감이 실제로 일어났는지 불확실) → compensate 시도 후 FAILED UPDATE

현재 구현은 `RiskManagementHttpClient`가 던지는 모든 예외(2xx 아닌 응답이든, 타임아웃이든, 커넥션 리셋이든)를 동일한 `RiskServiceException`으로 뭉뚱그리고(`RiskManagementHttpClient.java:50-51,55-60`), `CancelPaymentService`는 이를 구분 없이 catch해 **항상** compensate를 호출한다. 이번 커밋들에서 막 구현된 `isCharged(cancelRequestId)`(`RiskManagementHttpClient.java:82-100`)는 정확히 이 모호성을 해소하기 위한 API로 보이며 `PendingRecoveryService`(비동기 경로)에서는 "isCharged=true면 compensate, false면 그냥 FAILED"로 올바르게 사용되고 있으나, 동기 경로인 `CancelPaymentService`에는 연결되어 있지 않다.

한도 초과처럼 애초에 risk-management가 차감을 하지 않은 명확한 거부 응답에 대해서도 compensate가 호출되면, 차감된 적 없는 금액을 되돌리는 셈이 되어 `merchant_cancel_usage`의 사용량을 과대 복원(가맹점이 실제보다 더 많은 한도를 가진 것처럼) 시킬 위험이 있다. 이는 merchant-limit/risk 도메인의 핵심 불변식(일일 취소 한도 정확성)을 깨뜨린다.

**Fix:**
`isCharged()`를 재사용해 compensate 여부를 결정하거나, `RiskServiceException`에 HTTP 상태/에러 카테고리를 실어 "명확한 거부"와 "모호한 실패"를 구분할 것:

```java
} catch (Exception e) {
    if (riskManagementPort.isCharged(cancelRequest.getId())) {
        tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);
    }
    markFailed(cancelRequest, e.getMessage());
    throw e;
}
```

---

### CR-03: ProcessingRecoveryService.compensateAndFail()에 동시성 가드 부재 — 이중 보상 위험

**File:** `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java:86-159`

**Issue:**
`incrementPgRetryCount`는 원자 UPDATE + 재조회로 read-modify-write 경쟁을 제거했다(D-04, `CancelRequestJpaRepository.java:32-38`). 그러나 같은 파일의 `retryPgCancel`이 임계값 초과 시 호출하는 `compensateAndFail`(146-159행)과, `handleFailed`가 비재시도 실패에서 직접 호출하는 `compensateAndFail`은 **아무런 원자적 가드 없이** 실행된다:

```java
private void compensateAndFail(CancelRequest cancelRequest, Payment payment) {
    try {
        riskManagementPort.compensate(
            cancelRequest.getId(), payment.getMerchantId(), cancelRequest.getCancelAmount());
    } catch (Exception ex) {
        ...
        compensationRetryRepository.save(...);
    }
    cancelRequest.toFailed();
    cancelRequestRepository.save(cancelRequest);
    recordHistory(cancelRequest.getId(), CancelStatus.FAILED, "processing-recovery");
}
```

`CancelRequestJpaEntity`에는 `@Version` 필드가 없고, `cancelRequestRepository.save()`는 단순 UPDATE라 낙관적 락도 없다. 두 스레드(또는 두 파드)가 같은 PROCESSING 건을 동시에 집어 이 경로에 도달하면, 둘 다 독립적으로 `riskManagementPort.compensate()`를 호출한다 — TX3(`saveTx3`)의 경우 `findAllByPaymentIdForUpdate()` 행 락 + 도메인 상태 재검증으로 이중 실행이 막히지만(`ProcessingRecoveryConcurrencyIT` 검증(B)로 입증됨), compensate 경로에는 그런 재검증이 전혀 없다.

이 중복 처리 창은 이론적 가정이 아니다 — `ProcessingRecoveryScheduler.java`의 락은 `lock.tryLock(0, 55, TimeUnit.SECONDS)`로 **leaseTime을 고정 55초로 지정**(워치독 자동 연장 미사용)하고 스케줄 주기는 `fixedDelay = 60_000`이다. 한 파드의 처리가 55초를 넘기면 락이 자동 해제되고, 이때 다른 파드(또는 같은 파드의 다음 tick)가 동일한 stale PROCESSING 행 집합을 다시 조회해 병렬로 처리할 수 있다. 이 경우 `riskManagementPort.compensate()`가 같은 `cancelRequestId`에 대해 두 번 호출되어 가맹점 사용량이 과대 복원될 수 있다.

`ProcessingRecoveryConcurrencyIT`는 검증(A) 증가 경쟁, 검증(B) TX3(APPROVED) 경쟁만 다루며 FAILED/compensate 경로의 동시 실행은 테스트되지 않는다.

**Fix:**
FAILED 전이도 `incrementPgRetryCount`와 동일한 패턴(조건부 원자 UPDATE)으로 보호할 것. 예:

```sql
UPDATE cancel_request SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP(3)
 WHERE id = :id AND status = 'PROCESSING'
```

이 UPDATE의 반환 rowcount가 0이면(이미 다른 스레드가 선점) compensate 호출 자체를 스킵하도록 `compensateAndFail`을 재구성한다. 최소한 compensate 호출 자체는 반드시 "내가 FAILED 전이에 성공한 경우에만" 실행되도록 순서를 바꿔야 한다(현재는 compensate가 먼저, 상태 전이가 나중).

## Warnings

### WR-01: PG 상태값이 APPROVED/FAILED/PENDING 중 어느 것도 아니면 조용히 무시(로그 없음)

**File:** `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java:64-70`

**Issue:**
```java
if (result.isApproved()) {
    runTx3(cancelRequest, payment);
} else if (result.isFailed()) {
    handleFailed(cancelRequest, payment, result);
} else if (result.isPending()) {
    handlePgPending(cancelRequest, payment);
}
```
`PgCancelResult.status`는 PG 외부 시스템이 내려주는 자유 문자열이다(`PgCancelResult.java`, enum 아님). 세 상태 문자열 중 어느 것과도 일치하지 않는 값(오탈자, PG 스펙 변경, 신규 상태 추가 등)이 오면 이 블록은 아무 것도 하지 않고 조용히 지나간다 — PG 조회 실패 케이스(58-62행)는 최소한 `log.warn`이라도 남기는데, 이 케이스는 로그조차 없다. 해당 CancelRequest는 다음 사이클에도 계속 PROCESSING으로 남아 동일하게 무시되며 운영팀이 이를 알아챌 방법이 없다.

**Fix:**
```java
} else {
    log.warn("[processing-recovery] 알 수 없는 PG 상태={} cancelRequestId={}",
        result.status(), cancelRequest.getId());
}
```

### WR-02: 정상적인 레이스 패배(BusinessException)를 ERROR 레벨 "데이터 정합성 문제"로 로깅 — 알림 오탐

**File:** `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java:71-73`

**Issue:**
```java
} catch (BusinessException e) {
    log.error("[processing-recovery] 도메인 규칙 위반 — 데이터 정합성 문제 cancelRequestId={}: {}",
        cancelRequest.getId(), e.getMessage(), e);
}
```
`ProcessingRecoveryConcurrencyIT`의 주석(59-61행)은 동시 `saveTx3` 재실행 시 패자 스레드가 던지는 `InvalidPaymentItemStatusException`(BusinessException)을 "정상 레이스 결과"이자 "테스트 실패 조건이 아님"이라고 명시한다. 그런데 프로덕션 코드는 바로 이 동일한 예외 타입을 "데이터 정합성 문제"라는 ERROR 레벨 로그로 남긴다. 정상적인 동시 처리 상황마다 ERROR 로그/알림이 발생해 온콜 대응에 혼란을 주고, 진짜 정합성 문제가 발생했을 때의 신호 대 잡음비를 떨어뜨린다.

**Fix:** 레이스로 인해 기대되는 예외(예: `InvalidPaymentItemStatusException`)와 그 외 `BusinessException`을 구분하거나, 최소한 로그 레벨을 WARN으로 낮추고 메시지에서 "정합성 문제" 대신 "동시 처리 경쟁(예상됨)"으로 표현할 것.

### WR-03: RiskManagementHttpClient.compensate()가 응답 상태/바디를 검증하지 않음

**File:** `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:63-79`

**Issue:**
같은 클래스의 `validateAndReserve`(48-53행), `isCharged`(87-92행), 그리고 `PgCancelHttpClient`의 `cancel`/`getStatus`(46-49, 66-69행)는 모두 `response.getStatusCode().is2xxSuccessful() || response.getBody() == null` 가드를 명시적으로 두고 있다. 반면 `compensate()`는:

```java
circuitBreaker.executeCheckedSupplier(() -> {
    String url = baseUrl + "/internal/cancel-limit/compensate";
    Map<String, Object> request = Map.of(...);
    return restTemplate.postForEntity(url, request, Void.class);
});
```
상태 코드 검증이 전혀 없고 `Void.class`라 바디 파싱도 하지 않는다. `RestTemplate` 기본 에러 핸들러가 4xx/5xx에서 예외를 던지는 동작에 암묵적으로 의존하고 있어, 커스텀 `ResponseErrorHandler`가 적용되거나 risk-management가 200 OK와 함께 바디로 실패를 표현하는 규약으로 바뀌면 보상 실패가 성공으로 오인될 수 있다. compensate는 금전(한도 사용량 복원)에 직결되는 호출이므로 다른 메서드와 동일한 명시적 가드가 필요하다.

**Fix:**
```java
ResponseEntity<Void> response = restTemplate.postForEntity(url, request, Void.class);
if (!response.getStatusCode().is2xxSuccessful()) {
    throw new RiskServiceException("risk-management 보상 응답 오류: " + response.getStatusCode());
}
return response;
```

### WR-04: 서로 무관한 오퍼레이션이 CircuitBreaker 인스턴스를 공유

**File:** `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:19-33`, `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java:19-33`

**Issue:**
`RiskManagementHttpClient`는 `validateAndReserve`(취소 신청 경로의 필수 호출), `compensate`(보상 — 실패 시 돈이 안 맞음), `isCharged`(조회, pending-recovery 전용)가 생성자에서 주입되는 **동일한 `riskManagementCircuitBreaker` 인스턴스**를 공유한다. `PgCancelHttpClient`도 `cancel`/`getStatus`가 `pgCancelCircuitBreaker` 하나를 공유한다. 조회성 호출(`isCharged`, `getStatus`)이 일시적으로 실패율이 높아지면 서킷이 열려, 재무적으로 더 중요한 `compensate`/`cancel` 호출까지 함께 차단될 수 있다 — 특히 compensate는 이미 차감된 금액을 되돌리는 보상 경로라 차단되면 `compensation_retry`로 밀리며 지연이 커진다.

**Fix:** 최소한 조회(`isCharged`, `getStatus`)와 쓰기/보상(`validateAndReserve`, `compensate`, `cancel`) 오퍼레이션을 별도 CircuitBreaker 인스턴스로 분리하는 것을 검토할 것. 의도된 설계라면(예: PG/risk 서비스 자체의 장애를 통합 신호로 보고 싶은 경우) 주석으로 명시해 둘 것.

### WR-05: catch(Throwable t)가 Error까지 포섭해 애플리케이션 예외로 재포장

**File:** `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java:53-56,73-76`, `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:57-60,75-78,96-99`

**Issue:** 모든 HTTP 클라이언트 메서드가 `catch (Throwable t)`로 잡아 `PgServiceException`/`RiskServiceException`으로 재포장한다. `Throwable`은 `OutOfMemoryError`, `StackOverflowError` 같은 `Error`까지 포함하므로, JVM이 이미 불안정한 상태에서도 이를 "PG/risk 서비스 오류"로 위장해 정상적인 비즈니스 예외 흐름(보상, FAILED 처리 등)을 계속 진행시킨다. 이런 상황에서는 계속 진행하는 것보다 빠르게 실패(전파)하는 편이 안전하다.

**Fix:** `catch (Exception t)`로 좁히거나, `Error`는 별도로 재던지도록 분리:
```java
} catch (Error e) {
    throw e;
} catch (Throwable t) {
    ...
}
```

## Info

### IN-01: cancelRequestId 직렬화 타입이 엔드포인트마다 다름

**File:** `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:44,69,85`

**Issue:** `validateAndReserve`의 요청 바디는 `"cancelRequestId", String.valueOf(cancelRequestId)`(문자열)로 보내는데, `compensate`는 `"cancelRequestId", cancelRequestId`(long, Map.of를 통해 boxed Long)로, `isCharged`는 URI 템플릿 변수로 보낸다. 세 엔드포인트가 같은 필드명을 다른 JSON 타입으로 보내는 셈이라 risk-management 쪽 파싱이 타입에 엄격하면 문제가 될 수 있다. 일관되게 통일할 것.

### IN-02: CheckChargeResponseDto의 cancelRequestId 필드가 String이며 미사용

**File:** `payment-service/src/main/java/com/example/payment/application/dto/CheckChargeResponseDto.java:6`

**Issue:** `cancelRequestId`가 다른 곳에서는 `long`으로 다뤄지는데 이 DTO에서만 `String`이다. `RiskManagementHttpClient.isCharged()`는 `.charged()` 필드만 사용하고 이 필드는 읽지 않아 현재는 버그로 이어지지 않지만, 향후 사용 시 타입 캐스팅/파싱 실수를 유발할 수 있는 비일관성이다.

### IN-03: incrementPgRetryCount의 clearAutomatically=true — 향후 트랜잭션 경계 변경 시 지뢰

**File:** `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java:26-38`, `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java:50-78`

**Issue:** `@Modifying(flushAutomatically = true, clearAutomatically = true)`는 호출 시점의 영속성 컨텍스트 전체를 비운다. 현재 `recoverOne()`은 `@Transactional`이 아니므로 (각 리포지토리 호출이 자체 트랜잭션) 안전하지만, 이 메서드가 향후 더 큰 트랜잭션 경계 안으로 들어가게 되면 같은 트랜잭션에서 먼저 로드한 `payment` 등 엔티티가 방출되어 재사용 시 예기치 않은 재조회/`LazyInitializationException`을 유발할 수 있다. 코드 주석에 이미 "호출자는 반드시 재조회" 경고가 있어 현재로선 정보성 항목으로 남긴다 — 향후 `recoverOne`/`retryPgCancel`을 트랜잭션으로 감쌀 계획이 있다면 이 지뢰를 미리 문서화해 둘 것.

---

_Reviewed: 2026-07-29T03:59:13Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
