-- =============================================================
-- V17__create_stock_release_retry.sql
-- 예약 해제 보상 재시도: stock_release_retry (RSV-03, D-P2-6, compensation_retry 미러)
-- 예약 성공 후 payment 생성 TX 실패 + best-effort release 실패 시 적재.
-- payment_item이 롤백돼 없으므로 release 대상 items를 items_json에 보존한다.
-- =============================================================

CREATE TABLE stock_release_retry
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key   VARCHAR(64)  NOT NULL,
    items_json    VARCHAR(1000) NOT NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    last_error    VARCHAR(500) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_release_payment_key (payment_key),
    INDEX idx_stock_release_status_next (status, next_retry_at)
);
