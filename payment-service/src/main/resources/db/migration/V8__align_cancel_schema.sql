-- V8__align_cancel_schema.sql
-- cancel_request: idempotency_key → request_hash (payment_id, request_hash) UK
-- cancel_request_history: 상태 이력 테이블 신규
-- cancel_request_item: 제거 (아이템 전액 취소로 단순화)
-- payment_item: cancelled_amount 제거, PARTIAL_CANCELLED 상태 제거

-- 1. cancel_request 스키마 변경
ALTER TABLE cancel_request
    DROP KEY uk_cancel_request_idempotency_key,
    DROP COLUMN idempotency_key,
    DROP COLUMN canceller_type,
    DROP COLUMN cancelled_by,
    DROP COLUMN processing_started_at,
    DROP COLUMN failed_reason,
    ADD COLUMN request_hash     VARCHAR(64)   NOT NULL AFTER payment_id,
    ADD COLUMN pg_pending_since DATETIME(3)   NULL     AFTER status,
    ADD UNIQUE KEY uk_cancel_request_hash (payment_id, request_hash);

-- 2. cancel_request_history 신규 생성
CREATE TABLE cancel_request_history
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    reason            VARCHAR(500) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_cancel_request_history_cancel_request_id (cancel_request_id)
);

-- 3. cancel_request_item 제거 (아이템 전액 취소, 별도 테이블 불필요)
DROP TABLE IF EXISTS cancel_request_item;

-- 4. payment_item: cancelled_amount 제거
ALTER TABLE payment_item
    DROP COLUMN cancelled_amount;

-- 5. idempotency_key 테이블 제거 (request_hash UK로 대체)
DROP TABLE IF EXISTS idempotency_key;

-- 6. shedlock 테이블 제거 (Redis 분산락으로 대체)
DROP TABLE IF EXISTS shedlock;
