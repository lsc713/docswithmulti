-- 가맹점 유보 정책(원본). merchant_settlement_config(fee_rate) 와 동일 클래스(client 지정 PK, cross-DB FK 없음).
CREATE TABLE merchant_reserve_config
(
    merchant_id  BIGINT        NOT NULL,
    reserve_rate DECIMAL(5,4)  NOT NULL,          -- net 대비 유보율 (예: 0.0500 = 5%)
    reserve_cap  DECIMAL(19,2) NOT NULL,          -- 가맹점 누적 유보 상한
    hold_days    INT           NOT NULL,          -- 유보 → 릴리스 hold 기간(일)
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 유보금: 정산당 1건(uk_reserve_settlement). payout 의 평행 미니 상태머신(자체 RSV- 이체, PO- 와 분리).
CREATE TABLE reserve
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT        NOT NULL,
    merchant_id   BIGINT        NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,          -- 유보 금액(cap 반영, 승인 시 확정)
    status        VARCHAR(20)   NOT NULL,          -- HELD | RELEASING | RELEASED | RELEASE_FAILED | RELEASE_DEAD (DB enum 없음)
    hold_until    DATE          NOT NULL,          -- KST: today + hold_days
    transfer_ref  VARCHAR(120)  NOT NULL,          -- 이체 멱등키 = 'RSV-'+settlementId (결정적)
    attempt_count INT           NOT NULL DEFAULT 1,
    last_error    VARCHAR(500)  NULL,
    held_at       DATETIME(3)   NOT NULL,          -- 유보 생성(승인) 시각
    released_at   DATETIME(3)   NULL,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reserve_settlement (settlement_id),   -- 정산당 유보 1건 = 재승인·경합 안전
    KEY idx_reserve_status (status),                    -- 릴리스 스케줄러 select
    KEY idx_reserve_merchant (merchant_id)              -- 누적 held 합산(cap)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
