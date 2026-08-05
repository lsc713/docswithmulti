-- 가맹점 지급 계좌(원본). merchant_settlement_config 와 동일 클래스(client 지정 PK, cross-DB FK 없음).
CREATE TABLE merchant_payout_account
(
    merchant_id    BIGINT       NOT NULL,
    bank_code      VARCHAR(10)  NOT NULL,
    account_number VARCHAR(64)  NOT NULL,
    holder_name    VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 지급 건: FINALIZED 정산 헤더당 1건(uk_payout_settlement). transfer_ref = 'PO-'+settlement_id (승인 시 결정).
CREATE TABLE payout
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT        NOT NULL,
    merchant_id   BIGINT        NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,             -- net_amount 스냅샷(승인 시점)
    status        VARCHAR(20)   NOT NULL,             -- PROCESSING | PAID | FAILED (DB enum 없음)
    transfer_ref  VARCHAR(120)  NOT NULL,             -- 은행 제출 참조키(결정적 멱등키)
    attempt_count INT           NOT NULL DEFAULT 1,
    last_error    VARCHAR(500)  NULL,
    requested_at  DATETIME(3)   NOT NULL,             -- 승인/제출 시각
    paid_at       DATETIME(3)   NULL,                 -- PAID 확정 시각
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_settlement (settlement_id),  -- 정산당 1 지급 보장
    KEY idx_payout_status (status)                    -- poll: status='PROCESSING' 선별
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
