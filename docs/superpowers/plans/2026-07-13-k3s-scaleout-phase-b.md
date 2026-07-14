# k3s 스케일아웃 Phase B — 멀티인스턴스 정합성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** merchant-limit 아웃박스 폴러의 분산락을 수제 `SETNX`(버그 2종: 남의 락 삭제·고정 TTL 무갱신)에서 **Redisson RLock**(소유권 확인 unlock + 워치독 리스 갱신)으로 하드닝하고, 앱에 **graceful shutdown**을 설정한다.

**Architecture:** N-인스턴스에서 `@Scheduled` 폴러가 중복 발화하지 않도록 분산락을 payment 스케줄러와 동일한 Redisson 메커니즘으로 정렬. merchant-limit-service의 유일한 앱 코드 변경. 롤링/드레인 시 in-flight 취소가 잘리지 않도록 4개 앱에 graceful shutdown.

**Tech Stack:** Spring Boot 3.x · Redisson(`redisson-spring-boot-starter:4.3.1`, payment와 동일 좌표) · JUnit5 + Mockito · k8s Deployment.

## Global Constraints

- 앱 코드 변경은 **merchant-limit `OutboxPublisherScheduler` 하드닝 1건 + graceful shutdown 설정**뿐. 그 외 로직 불변.
- 분산락은 **payment 스케줄러(`ProcessingRecoveryScheduler`)와 동일한 Redisson RLock 패턴**으로 정렬 — 단 outbox 배치(최대 1000건) 발행 시간이 고정 TTL을 넘길 수 있어 **워치독(leaseTime 미지정) 사용**(payment는 고정 55s; 여기선 배치 지속시간 비한정성 때문에 워치독 선택).
- **차감/보상 semantics·멱등 불변식 불변**(CLAUDE.md). 테스트 없이 완료 금지.
- 무과금(로컬 테스트만). 클러스터 배포·재측정은 Phase C.

## 스코프 노트

스펙(`docs/superpowers/specs/2026-07-13-k3s-scaleout-design.md`) §4 중:
- **락 하드닝** → Task 1 (실 코드 + TDD)
- **graceful shutdown** → Task 2
- **프로브(readiness/liveness)** → **이미 충족**(Phase A 매니페스트가 `/actuator/health/{readiness,liveness}`로 프로브 설정, 파드가 Ready 도달 = Spring Boot가 k8s에서 probe 그룹 자동 활성). Phase B 추가 작업 없음.
- **검증 실험(스케줄러 락 on/off 재현 등)** → Phase C(별도 플랜, billable).

## File Structure

```
merchant-limit-service/
  build.gradle                                         (수정: redisson 의존 추가)
  src/main/.../infrastructure/messaging/OutboxPublisherScheduler.java   (재작성: Redisson RLock)
  src/main/.../infrastructure/config/RedisLockConfig.java               (삭제: 미사용 StringRedisTemplate)
  src/main/resources/application.yml                   (수정: lock-ttl-seconds 제거 + graceful shutdown)
  src/test/.../infrastructure/messaging/OutboxPublisherSchedulerTest.java (재작성: RLock mock)
payment-service/src/main/resources/application.yml     (수정: graceful shutdown)
risk-management-service/src/main/resources/application.yml (수정: graceful shutdown)
order-service/src/main/resources/application.yml       (수정: graceful shutdown)
infra/k8s/apps/{payment,risk,merchant-limit,order}.yaml (수정: terminationGracePeriodSeconds)
```

---

### Task 1: merchant-limit 아웃박스 폴러 락 하드닝 (Redisson RLock, TDD)

**Files:**
- Modify: `merchant-limit-service/build.gradle`
- Rewrite: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherScheduler.java`
- Delete: `merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/RedisLockConfig.java`
- Rewrite (test): `merchant-limit-service/src/test/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherSchedulerTest.java`
- Modify: `merchant-limit-service/src/main/resources/application.yml` (lock-ttl-seconds 제거)

**Interfaces:**
- Consumes: `RedissonClient`(redisson-spring-boot-starter 자동설정), `LimitEventOutboxRepository`, `LimitEventKafkaProducer`(불변).
- Produces: 생성자 시그니처 변경 `OutboxPublisherScheduler(LimitEventOutboxRepository, LimitEventKafkaProducer, RedissonClient)` (기존 3번째 인자 `StringRedisTemplate` → `RedissonClient`).

- [ ] **Step 1: build.gradle에 Redisson 의존 추가**

`merchant-limit-service/build.gradle`의 `dependencies` 블록에서 기존
```gradle
    // Redis (분산락)
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```
를 아래로 교체:
```gradle
    // Redis 분산락 — payment 스케줄러와 동일한 Redisson RLock 사용
    implementation 'org.redisson:redisson-spring-boot-starter:4.3.1'
```
(redisson-spring-boot-starter가 redis 커넥션 + `RedissonClient`를 자동설정. `spring.data.redis.*`(ConfigMap 주입)를 그대로 읽는다.)

- [ ] **Step 2: 실패 테스트 작성 (RLock mock으로 재작성)**

`OutboxPublisherSchedulerTest.java` 전체를 아래로 교체:

```java
package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository.PendingOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherScheduler (Redisson RLock)")
class OutboxPublisherSchedulerTest {

    @Mock LimitEventOutboxRepository outboxRepository;
    @Mock LimitEventKafkaProducer kafkaProducer;
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;

    OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaProducer, redissonClient);
        ReflectionTestUtils.setField(scheduler, "batchSize", 1000);
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:outbox:lock");
        when(redissonClient.getLock("test:outbox:lock")).thenReturn(lock);
    }

    @Test
    @DisplayName("락 획득 실패 — findPendingBatch·unlock 미호출(fail-safe skip)")
    void publish_lockNotAcquired_skips() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.publish();

        verify(outboxRepository, never()).findPendingBatch(anyInt());
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("락 보유 — 처리 후 소유권 확인하고 unlock")
    void publish_held_unlocksWhenOwner() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of());

        scheduler.publish();

        verify(lock).unlock();
    }

    @Test
    @DisplayName("finally에서 소유권 없으면(리스 만료 후 타 인스턴스 점유) unlock 미호출 — 남의 락 삭제 방지")
    void publish_notOwnerAtFinally_doesNotUnlock() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of());

        scheduler.publish();

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("pending 발행 성공 — markPublished 호출")
    void publish_pending_success_marksPublished() throws InterruptedException {
        PendingOutbox outbox = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(outbox));

        scheduler.publish();

        verify(kafkaProducer).publish(1L, "{\"merchantId\":1}");
        verify(outboxRepository).markPublished(10L);
    }

    @Test
    @DisplayName("발행 실패 — 실패건 markPublished 미호출·다음건 계속·unlock")
    void publish_failure_skipsMark_continuesNext_unlocks() throws InterruptedException {
        PendingOutbox o1 = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        PendingOutbox o2 = new PendingOutbox(11L, 2L, "{\"merchantId\":2}");
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(o1, o2));
        doThrow(new RuntimeException("kafka error")).when(kafkaProducer).publish(1L, "{\"merchantId\":1}");

        scheduler.publish();

        verify(outboxRepository, never()).markPublished(10L);
        verify(kafkaProducer).publish(2L, "{\"merchantId\":2}");
        verify(lock).unlock();
    }
}
```

- [ ] **Step 3: 테스트 실행 → 컴파일 실패(RED) 확인**

Run: `./gradlew :merchant-limit-service:test --tests '*OutboxPublisherSchedulerTest'`
Expected: **컴파일 실패** — `OutboxPublisherScheduler` 생성자가 아직 `StringRedisTemplate`를 받으므로 `RedissonClient` 인자와 불일치.

- [ ] **Step 4: OutboxPublisherScheduler 재작성 (Redisson RLock)**

`OutboxPublisherScheduler.java` 전체를 아래로 교체:

```java
package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * merchant.limit.updated 아웃박스 폴러. N-인스턴스에서 한 인스턴스만 발행하도록 분산락.
 * payment 스케줄러와 동일한 Redisson RLock 사용. 단 배치(최대 batchSize) 발행 시간이
 * 고정 TTL을 넘길 수 있어 leaseTime 미지정(워치독 자동 리스 갱신)으로 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final LimitEventOutboxRepository outboxRepository;
    private final LimitEventKafkaProducer kafkaProducer;
    private final RedissonClient redissonClient;

    @Value("${outbox.scheduler.batch-size:1000}")
    private int batchSize;

    @Value("${outbox.scheduler.lock-key}")
    private String lockKey;

    @Scheduled(fixedDelay = 10_000)
    public void publish() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // waitTime 0 + leaseTime 미지정 → 획득 못 하면 즉시 skip, 보유 중엔 워치독이 리스 자동연장.
            if (!lock.tryLock(0, TimeUnit.SECONDS)) {
                log.debug("Outbox 스케줄러 락 획득 실패 — 다른 인스턴스 실행 중");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<LimitEventOutboxRepository.PendingOutbox> pending =
                outboxRepository.findPendingBatch(batchSize);

            for (LimitEventOutboxRepository.PendingOutbox outbox : pending) {
                try {
                    kafkaProducer.publish(outbox.merchantId(), outbox.payload());
                    outboxRepository.markPublished(outbox.id());
                } catch (Exception e) {
                    log.error("Outbox 발행 실패. outboxId={}", outbox.id(), e);
                }
            }

            if (!pending.isEmpty()) {
                log.info("Outbox 발행 완료. count={}", pending.size());
            }
        } finally {
            // 소유권 확인 후 해제 — 리스 만료로 다른 인스턴스가 이미 잡았다면 그 락을 지우지 않는다.
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

- [ ] **Step 5: 미사용 StringRedisTemplate 정리 (RedisLockConfig 삭제 + yml 정리)**

`RedisLockConfig`의 `StringRedisTemplate` 빈이 이제 아무 데서도 안 쓰이는지 확인 후 삭제:
```bash
grep -rn 'StringRedisTemplate' merchant-limit-service/src/main   # 결과 없어야 함(있으면 삭제 보류)
git rm merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/RedisLockConfig.java
```
그리고 `merchant-limit-service/src/main/resources/application.yml`에서 워치독 전환으로 불필요해진 줄 제거:
```yaml
    lock-ttl-seconds: 9        # ← 이 줄 삭제 (lock-key 는 유지)
```

- [ ] **Step 6: 테스트 실행 → 통과(GREEN) 확인**

Run: `./gradlew :merchant-limit-service:test --tests '*OutboxPublisherSchedulerTest'`
Expected: **5개 테스트 PASS**.

- [ ] **Step 7: 모듈 전체 빌드 (회귀 없음 확인)**

Run: `./gradlew :merchant-limit-service:build`
Expected: `BUILD SUCCESSFUL` (기존 테스트 회귀 0, redisson 의존 정상 배선).

- [ ] **Step 8: Commit**

```bash
git add merchant-limit-service/build.gradle \
  merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherScheduler.java \
  merchant-limit-service/src/test/java/com/example/merchantlimit/infrastructure/messaging/OutboxPublisherSchedulerTest.java \
  merchant-limit-service/src/main/resources/application.yml
git rm merchant-limit-service/src/main/java/com/example/merchantlimit/infrastructure/config/RedisLockConfig.java
git commit -m "fix(merchant-limit): 아웃박스 폴러 분산락 Redisson RLock으로 하드닝

수제 SETNX+고정TTL+무조건 delete(남의 락 삭제·리스 무갱신 버그)를 payment와
동일한 Redisson RLock으로 정렬 — 소유권 확인 unlock + 워치독 리스 갱신.
N-인스턴스에서 배치 발행이 TTL 초과해도 이중발행 없음."
```

---

### Task 2: 앱 graceful shutdown 설정

**Files:**
- Modify: `payment-service/src/main/resources/application.yml`, `risk-management-service/src/main/resources/application.yml`, `merchant-limit-service/src/main/resources/application.yml`, `order-service/src/main/resources/application.yml`
- Modify: `infra/k8s/apps/payment.yaml`, `risk.yaml`, `merchant-limit.yaml`, `order.yaml`

**Interfaces:**
- Consumes: Phase A 매니페스트(각 Deployment `spec.template.spec`).
- Produces: 롤링/드레인 시 in-flight 요청 drain 후 종료(잘려도 pending/processing-recovery 스케줄러가 안전망).

- [ ] **Step 1: 4개 application.yml에 graceful shutdown 추가**

각 서비스 `application.yml`의 최상위(root)에 아래를 추가한다(기존 `server:`/`spring:` 블록이 있으면 그 아래 키를 병합, 없으면 블록 신설):

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s
```

- [ ] **Step 2: 4개 Deployment에 terminationGracePeriodSeconds 추가**

각 `infra/k8s/apps/<svc>.yaml`의 `spec.template.spec` 아래(`containers:`와 같은 레벨)에 추가:

```yaml
      terminationGracePeriodSeconds: 30
```

예 — `payment.yaml`의 `spec.template.spec`:
```yaml
    spec:
      terminationGracePeriodSeconds: 30
      affinity:
        podAntiAffinity:
          ...
```
(risk/merchant-limit/order는 `affinity` 블록이 없으므로 `terminationGracePeriodSeconds`를 `containers:` 위에 둔다.)

- [ ] **Step 3: 빌드 (설정 파싱·회귀 확인)**

Run: `./gradlew :payment-service:build :risk-management-service:build :merchant-limit-service:build :order-service:build`
Expected: 4개 모듈 `BUILD SUCCESSFUL`(yml 파싱 정상, 기존 테스트 회귀 0).
> 행동 검증(부하 중 롤링배포 시 5xx=0 drain)은 **Phase C 실험 5**에서 클러스터로 확인. 여기선 설정 반영 + 빌드까지.

- [ ] **Step 4: 매니페스트 yaml 문법 확인 (선택, 로컬)**

Run: `python3 -c "import yaml,glob; [yaml.safe_load_all(open(f)) and None for f in glob.glob('infra/k8s/apps/*.yaml')]; print('yaml OK')"`
Expected: `yaml OK` (문법 오류 없음).

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/resources/application.yml \
  risk-management-service/src/main/resources/application.yml \
  merchant-limit-service/src/main/resources/application.yml \
  order-service/src/main/resources/application.yml \
  infra/k8s/apps/payment.yaml infra/k8s/apps/risk.yaml \
  infra/k8s/apps/merchant-limit.yaml infra/k8s/apps/order.yaml
git commit -m "feat(k3s): 앱 graceful shutdown (롤링/드레인 시 in-flight 취소 drain)

server.shutdown=graceful + timeout-per-shutdown-phase 25s(4앱) +
Deployment terminationGracePeriodSeconds 30. 잘려도 recovery 스케줄러가 안전망."
```

---

## Self-Review

**Spec coverage (§4):**
- merchant-limit 락 하드닝(Redisson·소유권 unlock·워치독) → Task 1 ✓
- graceful shutdown → Task 2 ✓
- 프로브 → Phase A에서 이미 충족(매니페스트 프로브 + 파드 Ready 도달) → Phase B 작업 없음 ✓ 의도적
- Redis 단일 fail-safe → 코드로 보장(tryLock 실패 시 skip = Task 1 테스트 `publish_lockNotAcquired_skips`) ✓
- Kafka 컨슈머 리밸런싱 멱등 → 기존 `processed_cancel_event` UK(Phase A e2e에서 검증됨), 코드 변경 불필요 ✓

**Placeholder scan:** TBD/TODO 없음. yml 병합은 "기존 블록 있으면 병합"으로 구체 지시(실제 키·값 명시).

**Type consistency:** 생성자 시그니처 `OutboxPublisherScheduler(LimitEventOutboxRepository, LimitEventKafkaProducer, RedissonClient)`가 Task 1 Step 2(테스트)·Step 4(구현)에서 일치. `PendingOutbox(id, merchantId, payload)` 생성자는 기존 테스트와 동일. `lock.tryLock(0, TimeUnit.SECONDS)` 오버로드(워치독)가 테스트·구현에서 일치.

**확인(구현 시):** ① Step 5 `grep StringRedisTemplate` 결과 없어야 RedisLockConfig 삭제(있으면 보류·보고). ② redisson-spring-boot-starter가 `spring.data.redis.*`로 커넥션 잡는지 빌드+기존 IT로 확인(Step 7). ③ order-service application.yml 구조 확인 후 server/spring 블록 병합.

---

## Execution Handoff

Phase B는 무과금(로컬 TDD + 설정). Phase C(검증 실험 5개, billable)는 A·B 완료 후 별도 플랜.
