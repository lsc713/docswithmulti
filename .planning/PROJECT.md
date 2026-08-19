# PROJECT: 패션 이커머스 결제 취소 시스템

## 핵심 가치 (Core Value)

패션 이커머스의 **결제 취소**를 정합성 결함 없이(이중취소 0·초과차감 0·exactly-once)
처리하는 MSA. 4개 핵심 서비스(payment / order / merchant-limit / risk-management)는
이미 구현·부하실측·k3s 스케일아웃 검증까지 끝났다. 이 프로젝트의 지금 국면은
**신규 기능 확장이 아니라, 현재 인프라 위에서 시스템이 얼마나 잘 도는지 검증하고
구체적으로 개선하는 것**이다.

## 현재 마일스톤 (Milestone 1) — 현재 인프라 검증 & 개선

**한 줄 목표:** 실측된 포화 천장(~190 rps knee, ~220 절벽) 아래에서 취소 경로의
처리량·지연·운영성을 재현 가능하게 검증하고, 확정된 병목(payment_db)과 스케일아웃에서
드러난 정합성/배포 결함을 구체적으로 개선한다.

이미 만들어진 서비스를 **신규 페이즈로 다시 계획하지 않는다.** 과거의 빌드 플랜/설계
문서는 잠긴 맥락(locked context)으로 취급한다. Milestone 1은 오직 (1) 검증과
(2) 이미 코드가 존재하는 시스템의 개선에 한정한다.

## 성공 기준 (Definition of Done)

- 취소 처리량 천장(~190 rps knee)·p95/p99 지연을 **재현 가능한 절차**로 실측·문서화.
- 포화 병목(payment_db)이 **USE-method 관측 키트**로 실측 리그에서 재확인 가능.
- 스케일아웃에서 드러난 정합성/복구 결함(멀티파드 레이스 500, stubbed 복구 경로)을 제거.
- 무중단 롤링배포·노드장애 회복이 **매니페스트에 고정**되어 재현.
- 병목(payment_db)에 대해 **천장을 실제로 올리는 레버**를 실측으로 검증·적용.

## 제약 (Constraints)

- **스택 고정:** Java 21 · Spring Boot 3.x · Spring Data JPA + QueryDSL · MySQL 8.0
  (모듈별 독립 DB) · Flyway · Kafka 3.x(3-broker) · Gradle. 테스트 JUnit5 + Mockito +
  Testcontainers. 배포 k3s(수평 스케일아웃 진행 중).
- **모듈 격리:** 모듈 간 DB 직접 접근 금지 — HTTP 또는 Kafka 경유만.
- **레이어 규약:** domain 레이어에 Spring/JPA 어노테이션 금지. presentation → application
  → domain ← infrastructure 단방향.
- **불변식 준수:** `@CLAUDE.md` 핵심 불변식(멱등성 request_hash, TX1/2/3 경계, 이력은
  TX 밖, daily_limit 3단 조회 순서, 스케줄러 Redis 분산락)은 모두 잠긴 제약이다.
- **병목은 확정됨:** payment_db(2 vCPU, CPU+iowait+커밋 혼합 바운드). CPU 단독·풀 확대·
  flush=2 는 모두 실측 반증됨. 남은 on-target 레버는 storage IOPS와 취소당 커밋/round-trip
  감축, DB 인스턴스 클래스 상향. (출처: capacity-planning.md, k3s-scaleout-results.md)
- **측정 정직성 바:** 유리한 숫자만이 아니라 무엇을 숨기는지까지 기록. 크로스-리그
  절대 비교 금지(리그 내부 상대차만 신뢰).

## 잠긴 결정 (Locked Decisions)

<decisions>
<decision id="D-001" status="locked" date="2026-07-28">
  <title>payment.cancelled 는 TX3 인라인 발행 (Outbox 경로 폐기)</title>
  <statement>
    payment.cancelled 이벤트는 CancelTxWriter 안에서 TX3 종료 시점에
    kafkaTemplate.send() 로 인라인 발행한다. 파티션 키는 cancelRequestId.
    order 컨슈머가 전체 아이템 재계산 + 주문 행 락으로 순서 무관하게 수렴하므로
    결제 단위 순서 보장은 불필요(cancelRequestId 가 파티션 분산에 유리).
  </statement>
  <supersedes>
    docs/superpowers/specs/2026-04-27-payment-scheduler-design.md 의
    OutboxPublisher(payment.cancelled) 경로. 및 중간 단계였던
    AFTER_COMMIT(2026-04-28-simplified-messaging) 경로. 둘 다 폐기(provenance 로만 보존).
  </supersedes>
  <scope-note>
    Outbox 패턴은 merchant.limit.updated (파티션 키 merchantId) 에만 한정 유지.
    kafka-publish-pattern-benchmark 의 런타임 토글(INLINE/INLINE_ASYNC/OUTBOX)은
    벤치마크 실험용이며, 운영 기본은 INLINE 이다.
  </scope-note>
  <source>CLAUDE.md 핵심 불변식 · docs/kafka-design.md · 사용자 확인(2026-07-28 세션)</source>
</decision>

<decision id="D-002" status="locked" date="2026-07-28">
  <title>병목은 payment_db 로 확정 — 앱 티어 아님</title>
  <statement>
    취소 경로 포화 병목은 payment_db(2 vCPU)의 CPU+iowait+커밋 혼합이다.
    payment ×1→×3 (앱 Hikari), flush=2 (fsync 내구성), merchant 10→1000 (행 락)
    3종 개입이 모두 무릎을 못 옮겼고, payment_db vCPU 2→4 증설만 무릎을
    ~220→~260 으로 실제 이동시켰다(sub-proportional ~15%).
  </statement>
  <implication>
    개선 레버는 앱 티어 튜닝이 아니라 (a) storage IOPS(io2/gp3 상향),
    (b) 취소당 커밋/round-trip 감축, (c) DB 인스턴스 클래스 상향의 병행이다.
    단일 노브로는 비례 해소 불가.
  </implication>
  <source>docs/load-test/capacity-planning.md · docs/load-test/k3s-scaleout-results.md</source>
</decision>

<decision id="D-003" status="locked" date="2026-07-30">
  <title>payment.cancelled 는 OUTBOX 정식 발행 (D-001 정정·재역전)</title>
  <statement>
    payment.cancelled 이벤트는 TX3 안에서 cancel_event_outbox 에 원자 INSERT 후
    커밋되고, CancelEventOutboxPublisher(poll 10s + 커밋 직후 Redisson wake relay)가
    배치 발행한다. cancel.publish.mode 기본값은 OUTBOX(@ConditionalOnProperty
    matchIfMissing=true). INLINE/INLINE_ASYNC 는 벤치·학습 전용 토글로만 잔존.
    파티션 키는 여전히 cancelRequestId.
  </statement>
  <supersedes>
    D-001(2026-07-28 "TX3 인라인 발행, Outbox 폐기")을 정정한다. 2026-07-29
    cancel-outbox-redesign(docs/superpowers/specs/2026-07-29-cancel-outbox-redesign-design.md)
    이 main 에 반영되면서 운영 기본이 INLINE→OUTBOX 로 재역전됨. D-001 은
    provenance 로만 보존(더 이상 as-built 아님).
  </supersedes>
  <verification>
    origin/main 에 CancelEventOutboxPublisher 존재 + matchIfMissing=true 확인
    (2026-07-30). CLAUDE.md 핵심 불변식(OUTBOX 정식)과 일치.
  </verification>
  <source>CLAUDE.md · sysdesign/cancel-design.md · 코드 실측(origin/main, 2026-07-30)</source>
</decision>
</decisions>

## 범위 밖 / 향후 마일스톤 (Out of M1 Scope)

- **product-service (상품/SKU/재고):** 미구현. 신규 기능 마일스톤(M2 후보).
- **신규 기능 확장:** 재고 연동 restock, user/product 서비스, JWT 등 — 향후 마일스톤.
- **REQ-scale-blog-series:** 이미 완료된 스케일아웃 작업의 에디토리얼 산출물(9부 블로그).
  시스템 소프트웨어 변경이 아니므로 M1 시스템 검증/개선 범위 밖. 별도 콘텐츠 트랙.
- **카운터 샤딩 / admission control:** 핫 가맹점 단일 행 경합 대비 레버. 목표 DAU 가
  임계(~3천만)에 닿을 때만 착수(capacity-planning.md §6b). 현재 트리거 미도달.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-07-30 — Milestone v2.0 (auth-gateway workstream) started; D-003 정정 반영.*
