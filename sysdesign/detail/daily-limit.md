## 5. daily_limit 조회 전략 비교

### 5-1. 현재 설계 — DB 스냅샷

```
당일 첫 요청:
  merchant-limit-service HTTP 호출 → daily_limit 조회
  merchant_cancel_usage에 스냅샷 저장 (kst_date 기준)

이후 요청:
  merchant_cancel_usage 조회 (자체 DB)
  HTTP 호출 없음

한도 변경:
  다음날 첫 요청 시 반영
```

| 항목 | 평가 |
|------|------|
| 구현 복잡도 | 낮음 |
| 조회 속도 | 빠름 (자체 DB) |
| 정합성 | 당일 변경 반영 안 됨 |
| 인프라 추가 | 없음 |
| 장애 격리 | merchant-limit 장애 시 당일 첫 요청만 영향 |

---

### 5-2. Redis 우선 + DB 폴백

```
요청 시:
  Redis에서 merchantId:kstDate 키 조회
    → Hit: Redis 값 사용 (HTTP 호출 없음)
    → Miss: merchant-limit-service HTTP 호출
            → Redis에 KST 자정 TTL로 저장
            → 이후 요청은 Redis 사용

Redis TTL:
  KST 자정까지 남은 시간으로 설정
  → 다음날 자동 만료 → 새 daily_limit 자동 적용
```

```java
// findOrCreateUsage — Redis 우선 + HTTP 폴백 + DB 폴백
@Transactional
private MerchantCancelUsage findOrCreateUsage(
    Long merchantId, LocalDate kstDate
) {
    return merchantCancelUsageRepository
        .findByMerchantIdAndDateForUpdate(merchantId, kstDate)  // FOR UPDATE
        .orElseGet(() -> {
            // 당일 첫 요청 → daily_limit 조회 (Redis 우선)
            BigDecimal dailyLimit = getDailyLimitWithFallback(merchantId, kstDate);

            return merchantCancelUsageRepository.save(
                MerchantCancelUsage.create(merchantId, kstDate, dailyLimit)
            );
        });
}

private BigDecimal getDailyLimitWithFallback(
    Long merchantId, LocalDate kstDate
) {
    String key = "daily_limit:" + merchantId + ":" + kstDate;

    try {
        // 1순위: Redis 조회
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return new BigDecimal(cached);
        }

        // 2순위: merchant-limit-service HTTP 호출
        BigDecimal limit = merchantLimitClient.getDailyLimit(merchantId);

        // KST 자정까지 TTL 계산 후 Redis 저장
        Duration ttl = Duration.between(
            LocalDateTime.now(ZoneId.of("Asia/Seoul")),
            kstDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
                   .toLocalDateTime()
        );
        redisTemplate.opsForValue().set(key, limit.toString(), ttl);

        return limit;

    } catch (RedisException e) {
        // Redis 장애 → 3순위: HTTP 직접 호출 (Redis 없이)
        log.warn("Redis 조회 실패. HTTP 폴백: merchantId={}", merchantId, e);
        return merchantLimitClient.getDailyLimit(merchantId);
    }
}
```

```
Redis 장애 시 흐름:
  Redis 조회 실패 → catch
  → merchant-limit-service HTTP 직접 호출
  → 서비스 정상 유지 (Redis 없이도 동작)

Redis 장애 + merchant-limit 장애 시:
  당일 첫 요청만 실패
  이미 merchant_cancel_usage 행이 있는 요청은 정상 처리
```

| 항목 | 평가 |
|------|------|
| 구현 복잡도 | 중간 |
| 조회 속도 | 매우 빠름 (Redis in-memory) |
| 정합성 | 당일 변경 반영 안 됨 (TTL 만료 전까지) |
| 인프라 추가 | Redis 클러스터 필요 |
| 장애 격리 | Redis 장애 시 HTTP 폴백, 이중 안전망 |

---

### 5-3. 다른 대안들

**대안 1 — 매 요청마다 merchant-limit-service 직접 호출**

```
장점: 항상 최신 한도 반영
단점: 매 취소 요청마다 HTTP 호출 발생
      merchant-limit-service 장애 = 모든 취소 불가
      TPS 10,000에서 10,000 req/s HTTP 트래픽 발생
→ 성능/안정성 모두 취약 → 채택 불가
```

**대안 2 — Kafka 이벤트로 한도 변경 즉시 반영**

```
merchant-limit-service에서 한도 변경 시
Kafka 이벤트 발행 → risk-management-service가 consume
→ merchant_cancel_usage.daily_limit 즉시 업데이트

장점: 당일 한도 변경 즉시 반영
단점: Kafka 지연 시간 동안 반영 안 됨
      구현 복잡도 높음
      한도 변경이 드문 이벤트에 Kafka 연동 과잉
→ 한도 변경이 실시간 반영이 꼭 필요한 경우에만 채택
```

**대안 3 — Local Cache (Caffeine)**

```
Redis 없이 애플리케이션 메모리에 캐시

장점: 인프라 추가 없음, 매우 빠름
단점: 인스턴스마다 캐시가 다름
      인스턴스 A: daily_limit = 100만원 (구버전)
      인스턴스 B: daily_limit = 200만원 (신버전)
      → 같은 가맹점 요청이 어느 인스턴스로 가느냐에 따라 한도가 달라짐
→ 분산 환경에서 정합성 보장 불가 → 채택 불가
```

---

### 5-4. 전략 비교표

| 전략 | 속도 | 정합성 | 인프라 | 장애 격리 | 추천 |
|------|------|--------|--------|---------|------|
| DB 스냅샷 (현재) | 빠름 | 다음날 반영 | 없음 | 첫 요청만 영향 | 초기 단계 |
| Redis + DB 폴백 | 매우 빠름 | 다음날 반영 | Redis | Redis 장애 시 DB | TPS 1000+ |
| 매 요청 HTTP | 느림 | 즉시 반영 | 없음 | 한도서비스 다운=전체 장애 | 채택 불가 |
| Kafka 이벤트 | 빠름 | 수초 내 반영 | Kafka | 지연 가능 | 즉시 반영 필수 시 |
| Local Cache | 매우 빠름 | 인스턴스마다 다름 | 없음 | 없음 | 채택 불가 |

**현재 DB 스냅샷 선택 이유:**

```
취소 한도는 계약 기반이라 당일 즉시 반영이 불필요
추가 인프라(Redis) 없이 동일한 성능 달성
merchant-limit-service 장애가 당일 첫 요청에만 영향

TPS가 1,000을 넘어가는 시점에:
  당일 첫 요청 HTTP 호출이 병목이 될 수 있음
  → Redis + DB 폴백으로 전환 검토
```

---

### 5-5. 요구사항 변경 — 당일 즉시 반영이 필요한 경우

**문제:**

```
현재 설계:
  당일 첫 요청 시 daily_limit 스냅샷 저장
  이후 한도가 변경돼도 다음날까지 반영 안 됨

요구사항 변경:
  가맹점 한도 변경 시 당일 즉시 반영 필요
```

**대안 비교:**

| 대안 | 즉시 반영 | 결합도 | 복잡도 | 장애 격리 |
|------|---------|--------|--------|---------|
| 매 요청 HTTP | ✓ | 강함 | 낮음 | 취약 |
| Redis 키 삭제 (서비스 직접) | ✓ | 강함 | 중간 | 중간 |
| 캐시 무효화 API | ✓ | 중간 | 중간 | 중간 |
| Kafka 이벤트 (채택) | ✓ (수초) | 느슨 | 중간 | 좋음 |

**Kafka 이벤트 선택 이유:**

```
Redis 직접 접근 불가:
  Redis는 서비스별 독립 관리
  merchant-limit-service가 risk-management-service의
  Redis에 직접 접근하면 강한 결합 발생

캐시 무효화 API:
  merchant-limit-service → risk-management-service 의존 생김
  서비스 간 동기 결합

Kafka 이벤트:
  이미 Kafka 인프라 존재
  merchant-limit-service는 이벤트 발행만
  수신자가 누군지 몰라도 됨 → 느슨한 결합
  수초 지연은 계약 기반 한도 변경에서 허용 가능
```

**Kafka 이벤트 흐름:**

```
merchant-limit-service:
  한도 변경 → merchant.limit.updated 이벤트 발행
  { merchantId, newLimit, effectiveDate }
  끝. risk-management-service를 몰라도 됨

risk-management-service Consumer:
  이벤트 consume
  1. Redis 키 갱신
     daily_limit:merchantId:kstDate → newLimit
  2. merchant_cancel_usage 당일 행 UPDATE (있으면)
     SET daily_limit = newLimit
     WHERE merchant_id = ? AND kst_date = 오늘

이후 취소 요청:
  Redis에서 최신 daily_limit 조회
  → 변경된 한도 기준으로 검증
```

**장애 케이스별 대응:**

```
Redis 장애:
  merchant_cancel_usage.daily_limit으로 동작
  (Kafka consume 시 이미 업데이트됨)
  → 정상

merchant-limit-service 장애:
  Redis에 최신값 있으니 정상
  merchant_cancel_usage에도 있으니 이중 안전망

Kafka 지연:
  수초 내 이벤트 도달
  그 사이 취소 요청은 이전 한도 기준
  → 계약 기반 한도 변경이라 수초 허용 가능

둘 다 장애 (Redis + merchant-limit-service):
  merchant_cancel_usage.daily_limit으로 동작
  → risk-management-service 자체적으로 처리 가능
```

**merchant_cancel_usage 업데이트 주의:**

```
당일 행이 이미 존재할 때:
  기존: daily_limit=100만원, used_amount=80만원
  변경: daily_limit=200만원으로 UPDATE
  used_amount=80만원 유지
  → 이후 120만원 추가 취소 가능

당일 행이 없을 때 (첫 요청 전 한도 변경):
  Redis에 새 값 저장
  첫 요청 시 Redis에서 새 daily_limit 읽어서 행 생성
```

### 6-1. 별도 테이블 vs cancel_request에 합치기

**cancel_request에 합치는 방법:**

```sql
-- cancel_request에 이미 idempotency_key UK가 있음
-- response_body, expires_at 컬럼만 추가하면 됨
ALTER TABLE cancel_request
  ADD COLUMN response_body JSON NULL,
  ADD COLUMN expires_at DATETIME(3) NULL;
```

```java
// 재시도 시 조회
Optional<CancelRequest> existing =
    cancelRequestRepository.findByIdempotencyKey(idempotencyKey);

if (existing.isPresent() && existing.get().isCompleted()) {
    return deserialize(existing.get().getResponseBody());
}
```

**두 방식 비교:**

| 항목 | 별도 테이블 (현재) | cancel_request에 합치기 |
|------|-------------------|----------------------|
| 테이블 수 | +1 | 변경 없음 |
| 단일 책임 | 멱등성만 담당 | 멱등성 + 취소 요청 혼재 |
| TTL 관리 | 독립적으로 관리 | cancel_request와 생명주기 공유 |
| 범용성 | 다른 API에도 재사용 가능 | 취소 API 전용 |
| response_body NULL | 없음 | COMPLETED 아닌 건 NULL |

**합치면 안 되는 경우:**
```
결제 요청, 환불 등 다른 API도 멱등성이 필요하다면
cancel_request는 취소에 특화된 테이블
→ 범용 idempotency_key 테이블이 적합

취소 API만 멱등성이 필요하다면:
→ cancel_request에 합쳐도 됨 (테이블 수 절감)
```

**별도 테이블을 선택한 이유:**
```
1. 단일 책임: 멱등성 관리와 취소 요청 관리는 다른 역할
2. 범용성: 추후 결제, 환불 API도 동일 테이블로 멱등성 보장 가능
3. TTL 독립: expires_at 기반 만료 처리가 cancel_request와 무관
```

---

