-- payment.cancelled at-least-once 멱등 게이트 (RST-02, spec §8, D-P3-2/D-P3-4).
-- order-service processed_cancel_event 스키마 복제 — cancel_request_id UK로 중복 이벤트 no-op.
-- 적용 후 불변 — 변경은 새 버전(V3+)으로만.

CREATE TABLE processed_cancel_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64) NOT NULL,
    processed_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_cancel_event_cancel_request_id (cancel_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
