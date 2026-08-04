-- V19__create_payment_event_outbox.sql
-- 결제 생성 경로 payment.completed 아웃박스 (cancel_event_outbox V12+V14 동형).
-- 생성 TX 안에서 원자 INSERT(dual-write) → 폴 발행 스케줄러가 out-of-band 발행.
-- status 규약: PENDING | PUBLISHED | DEAD.
CREATE TABLE payment_event_outbox
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key  VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count  INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(500) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_event_outbox_key (payment_key),
    INDEX idx_payment_outbox_status_created_at (status, created_at)
);
