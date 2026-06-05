# 동기 HTTP 호출 내구성 설계

## 문제 정의

### 현재 취소 플로우의 HTTP 호출 체인

```
유저 → payment-service ──HTTP──→ risk-management-service ──HTTP──→ merchant-limit-service
                        ──HTTP──→ PG사 API
```

각 모듈은 독립 DB를 가지며, 모듈 간 통신은 HTTP(동기) 또는 Kafka(비동기)로 수행한다.
취소 플로우는 순차 검증이 필수이므로 동기 HTTP를 사용한다.

### TX 경계와 스레드의 관계

```
[톰캣 스레드 할당] ─────────────────────────────────────────── [톰캣 스레드 반환]
   │                                                              │
   ├── TX1 (DB 커넥션 사용 → 반환)                                  │
   ├── risk HTTP 대기 ← DB 커넥션 없음, 톰캣 스레드는 블로킹           │
   ├── TX2 (DB 커넥션 사용 → 반환)                                  │
   ├── PG HTTP 대기  ← DB 커넥션 없음, 톰캣 스레드는 블로킹            │
   └── TX3 (DB 커넥션 사용 → 반환)                                  │
```

**TX 경계를 잘 나눈 덕분에 DB 커넥션 고갈은 방지된다.**
HTTP 대기 구간에서 DB 커넥션을 점유하지 않기 때문이다.

**그러나 톰캣 스레드 고갈은 별개 문제다.**
`@Transactional`은 DB 커넥션 풀만 관리하며, 톰캣 서블릿 스레드와는 무관하다.
risk-management가 3초 걸리면 그 3초 동안 톰캣 스레드 1개가 아무 일 없이 블로킹된다.

---

## 현재 방어 장치 점검

### 잘 되어 있는 것

| 장치 | 위치 | 효과 |
|------|------|------|
| Circuit Breaker | payment → risk, risk → merchant-limit | 장애 서비스로의 요청 차단 (50% 실패 시 OPEN) |
| TX 경계 분리 | TX1/TX2/TX3 밖에서 HTTP 호출 | DB 커넥션 고갈 방지 |
| Redis 캐시 | risk 내부 daily_limit 조회 | merchant-limit HTTP 호출 빈도 대폭 감소 |
| DB 스냅샷 fallback | risk 내부 | Redis miss 시에도 merchant-limit HTTP 없이 처리 |
| 복구 스케줄러 | payment (3개) | PENDING/PROCESSING 타임아웃 자동 복구 |
| 멱등성 | request_hash UK | 재시도 시 중복 처리 방지 |

### 부재한 것

| 항목 | 현재 상태 | 위험 |
|------|----------|------|
| HTTP 타임아웃 | RestTemplate 기본값 (무제한) | 느린 응답에 스레드 무한 대기 |
| Bulkhead | 미설정 | risk 지연 시 전체 API 스레드 고갈 |
| 톰캣 스레드 풀 | 기본값 (200) | 명시적 튜닝 없음 |
| Virtual Threads | 미활성화 | Java 21이지만 활용 안 함 |

---

## 위험 시나리오 분석

### 시나리오 1: risk-management 응답 지연 (3초)

```
동시 취소 요청 200개 → 톰캣 스레드 200개 전부 risk 대기
→ 201번째 요청부터 결제 조회, 결제 생성 등 무관한 API도 거부됨
→ Circuit Breaker OPEN까지 최소 10건 필요 (slidingWindowSize=10)
→ 그 사이 수십~수백 스레드가 이미 블로킹
```

**핵심**: Circuit Breaker는 **실패 비율**로 동작하지, **지연**으로 동작하지 않는다.
느린 응답(타임아웃 없이)은 Circuit Breaker를 트리거하지 못한다.

### 시나리오 2: PG사 API 간헐적 타임아웃

```
PG 응답 10초 지연 → 스레드 10초 점유
→ 동시 요청 20건이면 200초 동안 200 스레드 점유 가능
→ 타임아웃 미설정이므로 기본값(무제한)에 따라 수분 대기할 수도 있음
```

### 시나리오 3: 가용성 곱셈

각 서비스 가용성 99.9% 가정:

```
취소 플로우 = payment(99.9%) × risk(99.9%) × PG(99.9%) = 99.7%
→ 월간 추가 다운타임 약 2시간
merchant-limit HTTP까지 포함하면 99.6% (월 약 3시간)
```

단, Redis/DB 스냅샷 fallback 덕분에 merchant-limit 호출은 드물어
실질적으로는 payment × risk × PG 체인만 고려하면 된다.

---

## 개선 방안

### 1단계: HTTP 타임아웃 설정 (즉시 적용, 가장 시급)

현재 RestTemplate이 타임아웃 없이 생성되어 있다.

#### payment-service HttpClientConfig 변경

```java
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));   // 연결 타임아웃 2초
        factory.setReadTimeout(Duration.ofSeconds(5));      // 응답 타임아웃 5초
        return new RestTemplate(factory);
    }
}
```

#### risk-management-service RestClient 변경

```java
@Bean("merchantLimitHttpClient")
public RestClient merchantLimitRestClient(
    @Value("${external.merchant-limit.base-url}") String baseUrl) {
    
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .build();
}
```

**효과**: 느린 응답을 빠르게 끊는다. 타임아웃 → 예외 → Circuit Breaker 실패 카운트 증가 →
빠르게 OPEN 상태 전환. 이미 복구 스케줄러가 있으므로 빠른 실패가 안전하다.

**타임아웃 값 근거**:
- risk-management 정상 응답: 50~200ms (Redis 캐시 히트 시)
- merchant-limit 정상 응답: 10~50ms (단순 DB 조회)
- PG사 API: 일반적으로 1~3초 이내
- 5초면 충분한 여유이며, 그 이상은 장애로 간주

---

### 2단계: Bulkhead (동시 실행 수 제한)

Semaphore Bulkhead를 사용한다. 별도 스레드 풀을 생성하는 것이 아니라,
**톰캣 스레드 풀 내에서 특정 호출의 동시 진입 수를 제한**하는 카운터다.

```
톰캣 스레드 풀 200개 (공유)
  ├── risk 호출: 세마포어 30 → 동시에 30개까지만 진입 허용
  ├── PG 호출:   세마포어 50 → 동시에 50개까지만 진입 허용
  └── 나머지:    120개는 다른 API에 사용 가능
```

31번째 risk 호출이 오면 `maxWaitDuration(500ms)` 동안 대기하다가,
자리가 안 나면 **스레드를 점유하지 않고 즉시 실패**(BulkheadFullException)한다.
→ 복구 스케줄러가 이후 재처리한다.

참고: ThreadPool Bulkhead(별도 스레드 풀 생성)도 있지만,
`CompletableFuture` 반환이 필요하므로 현재 동기 구조와 맞지 않는다.

#### payment-service Resilience4j Bulkhead 추가

```yaml
# application.yml
resilience4j:
  bulkhead:
    instances:
      risk-management:
        maxConcurrentCalls: 30        # risk 호출 최대 동시 30건
        maxWaitDuration: 500ms        # 대기열 초과 시 즉시 거부
      pg-cancel:
        maxConcurrentCalls: 50        # PG 호출 최대 동시 50건
        maxWaitDuration: 500ms
```

```java
// RiskManagementHttpClient.java — Bulkhead 적용
@Override
public RiskReserveResult validateAndReserve(...) {
    return Bulkhead.decorateCheckedSupplier(bulkhead,
        () -> circuitBreaker.executeCheckedSupplier(() -> {
            // 기존 로직
        })
    ).apply();
}
```

**효과**: risk가 느려져도 최대 30개 스레드만 블로킹. 나머지 170개 스레드는
결제 조회, 결제 생성 등 다른 API에 정상 사용 가능.

**값 근거**:
- 톰캣 기본 스레드 200개
- risk 호출에 30개 제한 → 나머지 170개는 다른 API용
- PG에 50개 제한 → 결제 취소 동시 처리량 충분
- 두 세마포어의 합(80)이 200을 넘지 않도록 설정

---

### 3단계: Virtual Threads (Java 21 활용)

Java 21의 Virtual Threads를 활성화하면 블로킹 HTTP 호출의 비용이 극적으로 줄어든다.
플랫폼 스레드 대신 경량 가상 스레드가 블로킹을 처리하므로, 수만 개의 동시 요청도 감당 가능.

#### 적용 방법 (Spring Boot 4.x)

```yaml
# application.yml — 각 모듈에 동일 적용
spring:
  threads:
    virtual:
      enabled: true
```

이 한 줄로 톰캣이 Virtual Threads를 사용한다.

**효과**:
- 톰캣 스레드 200개 제한이 사실상 사라짐
- HTTP 블로킹 대기가 OS 스레드를 점유하지 않음
- Bulkhead의 필요성이 줄어듦 (하지만 과부하 방지 차원에서 유지 권장)

**주의**:
- `synchronized` 블록 내부에서 블로킹하면 carrier thread pinning 발생
- ReentrantLock 사용 권장 (현재 코드에서 synchronized 사용 여부 확인 필요)
- Redisson 분산락은 내부적으로 Netty 기반이므로 Virtual Thread 호환 확인 필요

---

### 적용 우선순위

```
1단계: HTTP 타임아웃 설정        ← 즉시, 코드 변경 최소
       (RestTemplate/RestClient에 connect/read timeout 추가)

2단계: Bulkhead 적용             ← 1단계 이후
       (risk, PG 호출에 동시 실행 수 제한)

3단계: Virtual Threads 활성화    ← 검증 후 적용
       (spring.threads.virtual.enabled=true)
       (synchronized 사용 여부, Redisson 호환성 확인 선행)
```

1단계만으로도 "느린 응답에 무한 대기" 문제는 해결된다.
2단계까지 적용하면 "한 서비스 장애가 전체 API에 영향" 문제도 해결된다.
3단계는 근본적 해결이지만 호환성 검증이 필요하다.

---

## 비동기 전환은 필요한가?

취소 플로우는 순차 검증이 필수다:

```
한도 확인 → 한도 차감 → PG 취소 → 상태 변경
```

이 흐름을 비동기(이벤트 기반)로 바꾸면:
- 각 단계를 별도 이벤트로 분리해야 하고
- 중간 상태 관리가 복잡해지며 (Saga 패턴)
- 보상 트랜잭션 로직이 현재보다 훨씬 복잡해진다

현재의 **동기 호출 + Circuit Breaker + Bulkhead + 타임아웃 + 복구 스케줄러** 조합이
이 도메인에서는 최적이다. 비동기 전환의 복잡도 대비 이점이 크지 않다.

---

## 요약

| 문제 | 원인 | 해결 |
|------|------|------|
| 스레드 무한 블로킹 | HTTP 타임아웃 미설정 | 1단계: 타임아웃 설정 |
| 한 서비스 장애 → 전체 API 영향 | 스레드 격리 없음 | 2단계: Bulkhead |
| 톰캣 스레드 200개 한계 | 플랫폼 스레드 기반 | 3단계: Virtual Threads |
| DB 커넥션 고갈 | TX 밖에서 HTTP 호출 | **이미 해결됨** |
| 장애 서비스 반복 호출 | Circuit Breaker | **이미 해결됨** |
| merchant-limit 의존 | Redis + DB 스냅샷 fallback | **이미 해결됨** |
| 실패 건 방치 | 복구 스케줄러 3개 | **이미 해결됨** |
