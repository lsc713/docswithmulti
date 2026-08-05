# 정산 지급 실행(payout) — 설계 (settlement-service v2 슬라이스)

- 날짜: 2026-08-04
- 브랜치: `feat/settlement-payout` (worktree `../docswithmulti-payout`, 단독)
- 선행: settlement v1.0(취소·매출 원장 + fee/vat/net + OPEN→FINALIZED) main 반영(514251d). settlement-service :8086, Flyway V1.
- 정산 도메인의 두 번째 수직 슬라이스 — 확정된 net을 **실제 지급**까지 잇는다.

## 1. 목표 / 배경

settlement v1.0은 가맹점×정산주 net을 산출해 `settlement` 헤더에 **FINALIZED로 확정·영속**하는 데서 멈춘다(`net_amount` 이미 저장됨). 실제 지급(payout)·가맹점 정산계좌·이체 상태머신은 없다.

목표: **FINALIZED 정산의 net을 가맹점 계좌로 실제 지급**한다 — 관리자 승인으로 payout을 생성·제출하고, 비동기 이체 결과를 **웹훅(1차) + 폴(backstop)** 로 확인해 PAID/FAILED로 수렴시킨다. 이체는 목 은행(`@Profile("local")`)으로 시뮬레이션한다.

**불변 제약**: payment 취소 코어(및 payment/order/product/merchant 전 모듈)는 **한 줄도 안 바뀐다** — payout은 순수 settlement-service 추가(신규 테이블·엔티티·포트·어댑터·스케줄러·API). settlement `FINALIZED` 헤더도 불변(payout은 별도 엔티티가 수명주기를 가짐).

## 2. 스코프

**포함**
- 가맹점 정산계좌(`merchant_payout_account`) 설정·조회.
- FINALIZED 정산 관리자 승인 → `payout` 생성 + 목 은행 이체 제출.
- 비동기 이체 결과 확인: **웹훅 수신(서명검증)** + **폴 backstop 스케줄러**(Redisson) — 공유 `applyResult` 초크포인트로 순서무관 수렴·멱등.
- 실패 재시도(resubmit) + N회 초과 종단 FAILED + 알림.
- payout 상태 조회 API.

**범위 밖 (payout v3 → 각 별도)**
- 보류/유보(chargeback reserve) · 부분 지급
- 실은행 연동 · 실 서명 스킴(HMAC/mTLS) · 실 웹훅 재시도 정책
- 지급 취소·반환(refund/reversal of payout)
- 정산 명세서(statement) 발급 · 지급 스케줄(요일 지정 정기지급)
- 요율 차등/이력(정산 v1 잔여, 별개)

## 3. 데이터 모델 (Flyway V2, settlement_db 최신 V1 다음)

```sql
-- 가맹점 지급 계좌 (정산계좌). merchant_settlement_config 패턴 복제.
CREATE TABLE merchant_payout_account (
    merchant_id    BIGINT       NOT NULL,
    bank_code      VARCHAR(10)  NOT NULL,
    account_number VARCHAR(64)  NOT NULL,
    holder_name    VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 지급 레코드. 정산당 최대 1건(UK). settlement.status(FINALIZED)는 불변 — payout이 수명주기 소유.
CREATE TABLE payout (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT       NOT NULL,
    merchant_id   BIGINT       NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,          -- 승인 시점 settlement.net_amount 스냅샷
    status        VARCHAR(20)  NOT NULL,           -- PROCESSING | PAID | FAILED
    transfer_ref  VARCHAR(120) NOT NULL,           -- 이체 멱등키 (= payout id 문자열, 결정적)
    attempt_count INT          NOT NULL DEFAULT 1,
    last_error    VARCHAR(500) NULL,
    requested_at  DATETIME(3)  NOT NULL,           -- 승인·제출 시각
    paid_at       DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_settlement (settlement_id),   -- 정산당 1건 = 이중지급 차단
    KEY idx_payout_status (status)                      -- 폴 backstop 대상 조회
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 계좌는 settlement가 자체 소유(merchant_id 관례참조, cross-DB FK 없음).
- `transfer_ref = String(payout.id)` — 결정적 멱등키. 목 은행이 같은 ref 재제출을 dedup, 웹훅/폴이 같은 ref로 결과 대조.
- 금액은 승인 시점 `settlement.net_amount` 스냅샷(원장 FINALIZED 불변이라 항상 일치, 스냅샷은 감사·명세 안정성).

## 4. 도메인 규칙 / 상태머신

### 승인 (관리자)
`POST /v1/settlements/{id}/payout`:
- 가드: 대상 정산이 **FINALIZED** ∧ 해당 가맹점 **active 계좌 존재** ∧ **net_amount > 0** ∧ **payout 없음**.
  - 미FINALIZED / 계좌없음·비활성 / net≤0 → `400`. 이미 payout 존재(UK) → `409`(기존 반환).
- 통과 시: `payout` PROCESSING INSERT(`amount = net_amount`, `transfer_ref = id`) → `BankTransferPort.submit(transfer_ref, account, amount)`.
  - submit은 "접수(accepted)"만 반환(비동기) → payout은 PROCESSING 유지.

### 확인 (웹훅 1차 + 폴 backstop, 순서무관 수렴)
- **웹훅** `POST /v1/payouts/callback` `{transferRef, result(PAID|FAILED), signature}`:
  - **서명검증**: 공유 시크릿 헤더/필드 대조(`payout.webhook.secret`). 불일치 → `401`, 상태 미변경.
  - 검증 통과 → `applyResult(transferRef, result)`.
- **폴 backstop** Redisson `@Scheduled`(기본 60s): `status='PROCESSING' AND updated_at < now − grace`(웹훅 유실 의심분만) → `BankTransferPort.getStatus(transfer_ref)` → 종단이면 `applyResult`.
- **공유 초크포인트** `applyResult(transferRef, result)`:
  - **status-guarded 원자 UPDATE** `WHERE transfer_ref=? AND status='PROCESSING'`:
    - PAID → `status=PAID, paid_at=NOW`.
    - FAILED → `status=FAILED, last_error=…`.
  - 0행 갱신(이미 종단) → **no-op**(웹훅·폴 중복/경합 멱등, cancel-restore "레그 무관 수렴"과 동형).

### 실패 재시도
- FAILED payout은 폴 스케줄러가 재제출(resubmit): `attempt_count < max` 이면 `submit` 재호출 + PROCESSING 복귀(`attempt_count++`), `transfer_ref` 동일(목 은행 dedup).
- `attempt_count ≥ max`(기본 5) → 종단 FAILED 유지 + `OperationAlertPort` 알림(1회, 재알림 억제).

## 5. API 계약

- `PUT /v1/settlements/payout-account/{merchantId}` `{bankCode, accountNumber, holderName}` → 계좌 upsert(활성). 유효성(빈 값·형식) 위반 → `400`.
- `GET /v1/settlements/payout-account/{merchantId}` → 계좌 조회(없으면 `404`).
- `POST /v1/settlements/{id}/payout` → 승인·제출(§4 가드). 응답: payout 요약(id·status·amount).
- `GET /v1/settlements/{id}/payout` → payout 상태 조회(없으면 `404`).
- `POST /v1/payouts/callback` `{transferRef, result, signature}` → 이체 결과 웹훅(서명검증·멱등). 게이트웨이 인증 라우트 아님(은행→서비스, 서명으로 인증).

## 6. 외부 목 (BankTransferPort)

- 포트 `application/interfaces/BankTransferPort`: `submit(transferRef, account, amount) → TransferAck(accepted)` · `getStatus(transferRef) → TransferStatus(PROCESSING|PAID|FAILED)`.
- 목 `infrastructure/http/MockBankTransferClient` (`@Profile("local") @Component`): submit은 accepted 반환·내부 상태 PROCESSING, getStatus는 (테스트 결정성 위해) 지정 시나리오대로 PAID/FAILED 반환. 실 HTTP impl `BankTransferHttpClient`는 스텁(다른 프로필).
- 웹훅 경로 테스트: 테스트가 `POST /v1/payouts/callback`을 직접 호출(목 은행의 push 시뮬레이션) + 서명 헤더 포함.
- 참조: payment `PgCancelPort` / `MockPgCancelClient`(`@Profile("local")`) 패턴 복제.

## 7. 아키텍처 / 레이어 (기존 헥사고날 답습)

- `domain/entity`: Payout(상태전이 규칙 POJO), MerchantPayoutAccount.
- `application/interfaces`: BankTransferPort, PayoutRepository/MerchantPayoutAccountRepository 포트, OperationAlertPort(기존 재사용).
- `application/service`: PayoutService(승인·제출), PayoutResultService(applyResult 공유 초크포인트), PayoutRetryScheduler 위임 로직.
- `infrastructure/persistence`: payout·merchant_payout_account JPA 엔티티/리포지토리/Impl + PersistenceConfig `@Bean` 배선.
- `infrastructure/http`: MockBankTransferClient(+실 스텁), 서명검증 유틸.
- `infrastructure/scheduler`: PayoutPollScheduler(Redisson 락, reconcile 스케줄러 복제).
- `presentation`: PayoutController(승인·조회), PayoutAccountController(계좌), PayoutCallbackController(웹훅).

## 8. 불변식 / 가드

- **settlement-only(INV)**: payment/order/product/merchant 무접촉. merge-base git diff — 취소 코어 denylist(기존) diff 0 + 변경이 `settlement-service/`(+`.planning/`·`docs/`) 국한. **payment allowlist 확장 불필요**(reconcile와 달리 payout은 payment 조회조차 안 함 — net은 settlement가 이미 보유). Flyway **V2만** 추가(V1 무변경).
- **이중지급 방지**: `payout.settlement_id` UK + `transfer_ref` 결정적 + `applyResult`의 status-guarded UPDATE(재진입·중복콜백 no-op).
- **settlement 헤더 불변**: payout은 별도 엔티티 — settlement.status/net 미변경(v1 FINALIZED 불변식 보존).
- **금액 규약**: `payout.amount`는 승인 시 `net_amount` 스냅샷, DECIMAL(19,2) KRW.

## 9. 테스트 전략 (Testcontainers MySQL + Redis)

- 계좌 upsert/조회(유효성 400, 미존재 404).
- 승인 가드: 미FINALIZED·계좌없음·net≤0 → 400, 중복승인 → 409(기존 반환), 정상 → PROCESSING + submit 호출.
- 웹훅: 서명검증(불일치 401·상태불변), 정상 PAID/FAILED 반영.
- 폴 backstop: 웹훅 미도착(PROCESSING 방치) → 스케줄러 getStatus → 수렴.
- **순서무관 수렴·멱등**: 웹훅과 폴이 같은 payout에 도착 → 종단 1회만 반영(둘째 no-op), 이중지급 없음.
- 실패 재시도: FAILED → resubmit(attempt++) → max 초과 → 종단 FAILED + 알림 1회.
- INV: 취소 코어 diff 0 + 변경 settlement-service 국한 + Flyway V2 only. payment/order/product 무회귀.

## 10. 열린 질문 (계획 단계 확정)

- 폴 backstop 주기·grace(웹훅 유실 판정 임계) · 재시도 max·백오프.
- 웹훅 서명 스킴 상세(공유시크릿 단순 대조 vs HMAC 본문서명) — 목 수준 결정.
- payout PAID 시 settlement에 지급완료를 표시할지(조회 편의) vs payout 조인으로만 노출(헤더 불변 유지) — 후자 기본.
- 계좌 인가(누가 PUT 하나) — 게이트웨이 신뢰헤더 ADMIN/본인 가맹점, 배포 시 NetworkPolicy(정산 조회 API와 동일 클래스).
- `net = 0` 정산(취소가 매출 상쇄) 처리 — 지급대상 아님(승인 400), 별도 CLOSED 표기 여부는 범위 밖.
