# 정산 유보금(payout reserve / 롤링 리저브) — 설계 (settlement-service v3 슬라이스)

- 날짜: 2026-08-05
- 브랜치: `feat/settlement-payout-reserve` (worktree `../docswithmulti-reserve`, origin/main 44b9abb에서 분기 — payout v1.0 포함)
- 선행: payout v1.0(승인→제출→웹훅+폴 확인→PAID, 재시도/DEAD) origin/main 반영(#100, 44b9abb). settlement-service :8086, Flyway V2.
- 정산 도메인의 세 번째 수직 슬라이스 — 지급 시 net의 일부를 유보하고 hold 후 릴리스.

## 1. 목표 / 배경

payout v1.0은 FINALIZED 정산의 net **전액**을 가맹점에 지급한다. 실무 정산은 향후 취소·환불·분쟁 리스크에 대비해 net의 일부를 **유보(reserve/holdback)**하고, 일정 기간(hold) 후 릴리스한다(rolling reserve).

목표: 지급 승인 시 **net의 일부를 유보**(가맹점별 요율 + 누적 상한)하여 payout을 `net − reserve`로 줄이고, 유보금을 **hold 기간 후 자체 은행이체로 릴리스**한다.

**성격(범위 한정)**: 이번 슬라이스의 reserve는 **시간기반 롤링 홀드백**이다 — net%를 hold → `hold_days` 후 릴리스. 실제 취소·환불이 유보금을 **소진(draw down)**하는 chargeback 원장은 **범위 밖(v4)**. reserve는 "지연 지급 완충"이며, 발생한 취소가 reserve를 자동 차감하지 않는다.

**불변 제약**: payment/order/product/merchant 무접촉(settlement-only). payout 코어(approve의 409 race 불변·applyResult 수렴·retry/DEAD)를 깨지 않는다 — 특히 `approve()`를 `@Transactional`로 감싸지 않는다.

## 2. 스코프

**포함**
- 가맹점 유보 정책 config(`merchant_reserve_config`: rate + cap + hold_days) 설정·조회.
- payout 승인 시 유보 차감: `payout = net − reserve`(cap 반영), `reserve` HELD 행 생성.
- 유보금 릴리스: hold 만기 → 자체 은행이체(`RSV-` 이체) → 웹훅(1차)+폴(backstop) 확인 → RELEASED.
- 릴리스 실패 재시도 + max 초과 RELEASE_DEAD + 알림.
- 유보 상태·잔액 조회.

**범위 밖 (reserve v4 → 각 별도)**
- 취소·환불의 reserve 실제 소진(chargeback drawdown) — 발생 취소가 held 유보금을 차감.
- 부분 릴리스 · 만기 전 강제 릴리스/조정 · 유보 요율 이력(effective-dated).
- 실은행 연동 · 실 서명 스킴(HMAC/mTLS).

## 3. 데이터 모델 (Flyway V3, settlement_db 최신 V2 다음)

```sql
-- 가맹점 유보 정책. merchant_settlement_config(fee_rate) 패턴.
CREATE TABLE merchant_reserve_config (
    merchant_id  BIGINT       NOT NULL,
    reserve_rate DECIMAL(5,4) NOT NULL,          -- net 대비 유보율 (예: 0.0500 = 5%)
    reserve_cap  DECIMAL(19,2) NOT NULL,         -- 가맹점 누적 유보 상한
    hold_days    INT          NOT NULL,          -- 유보 → 릴리스 hold 기간(일)
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 유보금. 정산당 1건(UK). payout의 평행 미니 상태머신(자체 RSV- 이체).
CREATE TABLE reserve (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT       NOT NULL,
    merchant_id   BIGINT       NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,         -- 유보 금액(cap 반영, 승인 시 확정)
    status        VARCHAR(20)  NOT NULL,          -- HELD | RELEASING | RELEASED | RELEASE_FAILED | RELEASE_DEAD
    hold_until    DATE         NOT NULL,          -- KST: today + hold_days
    transfer_ref  VARCHAR(120) NOT NULL,          -- 이체 멱등키 = 'RSV-'+settlementId (결정적)
    attempt_count INT          NOT NULL DEFAULT 1,
    last_error    VARCHAR(500) NULL,
    held_at       DATETIME(3)  NOT NULL,          -- 유보 생성(승인) 시각
    released_at   DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reserve_settlement (settlement_id),   -- 정산당 유보 1건 = 재승인·경합 안전
    KEY idx_reserve_status (status),                    -- 릴리스 스케줄러 select
    KEY idx_reserve_merchant (merchant_id)              -- 누적 held 합산(cap)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 유보금 릴리스는 **자체 이체**(`transfer_ref='RSV-'+settlementId`) — payout `uk_payout_settlement`(정산당 payout 1건)을 건드리지 않는다. RSV-/PO- 네임스페이스 분리.
- `transfer_ref`는 `RSV-{settlementId}`로 승인 시점 확정(auto id 순환 회피, 정산당 유보 1건이라 유일).

## 4. 도메인 규칙

### 유보 산정 (승인 시, cap 반영)
```
desired      = round(net × reserve_rate, 2, HALF_UP)
current_held = Σ reserve.amount WHERE merchant_id=? AND status IN ('HELD','RELEASING')
reserve      = min(desired, max(0, reserve_cap − current_held))
payout       = net − reserve
hold_until   = LocalDate.now(KST) + hold_days
```
- **config 없음 / active=false / rate 0 / cap 소진(current_held ≥ cap)** → `reserve = 0` → `payout = net`. **하위호환**: 유보 미설정 가맹점의 payout은 기존과 동일.
- `current_held`는 아직 릴리스 안 된 유보(HELD·RELEASING)만 합산 — RELEASED는 상한에서 빠진다(롤링).
- 반올림 HALF_UP scale 2, BigDecimal, KRW.

### 승인 순서 (approve non-@Transactional 불변)
1. 유보 산정(읽기: config + Σheld).
2. **payout PROCESSING INSERT**(`amount = net − reserve`) — 기존 409 race 가드(loser는 `uk_payout_settlement` DIVE→409, 여기서 멈춰 reserve 미생성).
3. **reserve HELD INSERT**(`amount = reserve`, `hold_until`, `transfer_ref='RSV-'+settlementId`) — reserve=0이면 생략(행 미생성).
4. payout `BankTransferPort.submit`.

*수용 엣지: 2와 3 사이 크래시 창(payout은 net−reserve로 커밋됐으나 reserve 행 없음)은 리컨실로 탐지 가능(범위 밖). `approve()`를 `@Transactional`로 감싸면 payout INSERT가 커밋 시점으로 밀려 DIVE가 catch를 벗어나 409→500 회귀하므로, 별도 write 순서를 유지한다.*

### 릴리스 상태머신 (Phase 2)
- 릴리스 스케줄러(Redisson, 기본 일 1회): `status='HELD' AND hold_until < today(KST)` 선택.
- **claim** `UPDATE reserve SET status='RELEASING' WHERE transfer_ref=? AND status='HELD'`(guarded, rowcount==1만 승자) → `BankTransferPort.submit('RSV-'+settlementId, account, amount)`.
- **확인**: reserve 웹훅 `POST /v1/reserves/callback`(서명검증) + 폴 backstop → 공유 `applyReserveResult(transferRef, result)` = `UPDATE ... WHERE transfer_ref=? AND status='RELEASING'` → RELEASED(released_at)/RELEASE_FAILED. 0행 no-op(순서무관 수렴·멱등, payout applyResult 동형).
- **재시도**: RELEASE_FAILED → 스케줄러가 재제출(attempt_count<max, 동일 transfer_ref) → RELEASING 복귀. max 초과 → RELEASE_DEAD + `OperationAlertPort` 알림 1회(재알림 억제, payout retry/DEAD 동형).
- 릴리스는 가맹점 **active 계좌** 필요(payout과 동일). 계좌 없으면 skip+log, HELD 유지.

## 5. API 계약

- `PUT /v1/settlements/reserve-config/{merchantId}` `{reserveRate, reserveCap, holdDays}` → upsert. 유효성(rate 0≤r<1·scale≤4, cap≥0, holdDays≥0) 위반 → 400.
- `GET /v1/settlements/reserve-config/{merchantId}` → 조회(없으면 404).
- `GET /v1/settlements/{id}/reserve` → 유보 상태(없으면 404).
- `GET /v1/merchants/{merchantId}/reserve-balance` → 현재 held 합(HELD·RELEASING) — 선택.
- `POST /v1/reserves/callback` `{transferRef, result, signature}` → 릴리스 이체 결과 웹훅(서명검증·멱등). 게이트웨이 비경유(은행→서비스).

## 6. 외부 목 (BankTransferPort 재사용)

payout의 `BankTransferPort`(submit/getStatus) + `@Profile("local") MockBankTransferClient`를 그대로 재사용 — 이체 대상만 `RSV-` ref. 신규 포트 없음. 릴리스 웹훅 테스트는 `POST /v1/reserves/callback`을 직접 호출(목 은행 push 시뮬레이션).

## 7. 아키텍처 / 레이어

- `domain/entity`: Reserve(상태전이 POJO), MerchantReserveConfig.
- `application/service`: ReserveConfigService(정책), PayoutService **확장**(승인 유보 차감 배선), ReserveReleaseService(claim/applyReserveResult/retry — payout ResultService 평행), ReserveQueryService.
- `application/interfaces` + `infrastructure/persistence`: reserve·merchant_reserve_config 포트/JPA/Impl + PersistenceConfig @Bean 배선.
- `infrastructure/scheduler`: ReserveReleaseScheduler(Redisson, PayoutPollScheduler 클론, 신규 락키·기존 RedissonClient 재사용).
- `presentation`: ReserveConfigController·ReserveQueryController·ReserveCallbackController.

## 8. 불변식 / 가드

- **settlement-only(INV)**: payment/order/product/merchant diff 0. 변경이 `settlement-service/`(+`.planning/`·`docs/`) 국한. **Flyway V3 추가 허용**(정산 마이그레이션 — payment 마이그레이션만 금지). merge-base git diff 게이트(payout 02-03 클론).
- **payout 코어 불변**: `approve()` non-@Transactional·409 race·applyResult 수렴·retry/DEAD 로직 무변경(승인에 유보 차감 배선만 추가). 기존 payout 통합테스트 무회귀(유보 미설정 → net 그대로).
- **이중 릴리스 방지**: `reserve.settlement_id` UK + `transfer_ref='RSV-'+settlementId` 결정적 + `applyReserveResult` status-guarded UPDATE(재진입·중복콜백 no-op).
- **cap 정확성**: `current_held`는 HELD·RELEASING만 합산, 승인마다 재계산.
- **금액 규약**: BigDecimal scale 2 HALF_UP, DECIMAL(19,2) KRW.

## 9. 테스트 전략 (Testcontainers MySQL + mock Redisson)

- reserve config upsert/조회(유효성 400, 미존재 404).
- 유보 산정: rate 적용(net×rate), cap 반영(min(desired, cap−held)), config 없음/비활성 → reserve 0·payout=net(하위호환), cap 소진 → reserve 0.
- 승인 결합: payout=net−reserve + reserve HELD 생성 + hold_until=today(KST)+holdDays. 기존 409 race·payout retry 무회귀.
- 릴리스: 만기(hold_until<today) → claim HELD→RELEASING → submit; 웹훅 서명(불일치 401), 폴 backstop, 순서무관 수렴(웹훅↔폴 종단 1회), RELEASED.
- 릴리스 실패 재시도 → RELEASE_DEAD + 알림 1회(재알림 없음), 이중 릴리스 없음.
- INV: 취소 코어 diff 0 + settlement-only + V3만(payment 마이그레이션 0). 4모듈 무회귀.

## 10. 열린 질문 (계획 단계 확정)

- 릴리스 스케줄러 주기·hold_until 경계(포함/배타) · 재시도 max·backoff.
- reserve 웹훅과 payout 웹훅 엔드포인트 분리 vs transfer_ref 접두사 디스패치(현재: 분리 `/v1/reserves/callback`).
- cap 소진 시 payout 로그/알림 여부(현재: 조용히 reserve 0).
- `current_held` 합산에 RELEASE_FAILED/RELEASE_DEAD 포함 여부(현재 제외 — 실패 유보는 상한 점유 안 함; 재검토 여지).
- 릴리스 계좌가 승인 시점과 달라졌을 때(계좌 변경) 정책 — 현재 릴리스 시점 active 계좌 사용.
