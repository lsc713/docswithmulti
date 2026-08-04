-- 가맹점 정산 설정(요율 원본). merchant-limit-service의 merchant.id를 관례 참조(cross-DB FK 없음).
CREATE TABLE merchant_settlement_config
(
    merchant_id BIGINT       NOT NULL,
    fee_rate    DECIMAL(5,4) NOT NULL,          -- 예: 0.0330 = 3.3%
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 원장 헤더: 가맹점 × 정산주(KST 월~일) 유일.
CREATE TABLE settlement
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id   BIGINT        NOT NULL,
    period_start  DATE          NOT NULL,          -- 정산주 월요일(KST)
    period_end    DATE          NOT NULL,          -- 정산주 일요일(KST)
    gross_amount  DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 완료 매출 합
    cancel_amount DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 취소 거래액 합
    fee_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 수수료(확정 시 계산)
    vat_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 수수료 VAT(확정 시 계산)
    net_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 지급 예정액(확정 시 계산)
    status        VARCHAR(20)   NOT NULL,             -- OPEN | FINALIZED
    finalized_at  DATETIME(3)   NULL,
    created_at    DATETIME(3)   NOT NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_merchant_period (merchant_id, period_start),
    KEY idx_settlement_status_period (status, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 원장 라인: 감사추적 + 멱등 단위. 이벤트/리컨실이 여기에 append.
CREATE TABLE settlement_line
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT        NOT NULL,
    type          VARCHAR(10)   NOT NULL,             -- SALE | CANCEL
    payment_key   VARCHAR(100)  NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,             -- 양수(부호는 type이 결정)
    event_id      VARCHAR(120)  NOT NULL,             -- 멱등키(cancel:{cancelRequestId})
    occurred_at   DATETIME(3)   NOT NULL,             -- 매출/취소 발생시각(정산주 귀속 기준)
    created_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_line_event (event_id),   -- 중복 적재 차단
    KEY idx_settlement_line_settlement (settlement_id),
    CONSTRAINT fk_settlement_line_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 컨슈머 멱등(이벤트 재수신 no-op). 라인 UK와 이중 가드.
CREATE TABLE processed_settlement_event
(
    event_id     VARCHAR(120) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
