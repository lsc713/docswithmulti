-- 취소 복원 durable DLQ (LOSS-01/DLQ-01/DLQ-02/REDRIVE-01/02, Phase 1 product V5 미러).
-- 재시도 소진(count>=3) 또는 NonRetryable 실패 시 이 테이블에 적재 + 알림 → durable 진실.
-- 재구동 스케줄러가 PENDING 을 재구동, 성공 RESOLVED / 임계 초과 DEAD.
-- order/product 복제(공용 추상화 안 함) — leg 컬럼으로 구분(order='ORDER').
-- 적용 후 불변 — 변경은 새 버전(V5+)으로만.
-- status 도메인: PENDING / RESOLVED / DEAD 세 가지.

CREATE TABLE cancel_restore_dlq (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)  NOT NULL,
    leg               VARCHAR(16)  NOT NULL,
    payload           TEXT         NOT NULL,
    retry_count       INT          NOT NULL DEFAULT 0,
    attempt_count     INT          NOT NULL DEFAULT 0,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    first_failed_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error        VARCHAR(512) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_restore_dlq_cancel_request_id (cancel_request_id),
    KEY idx_cancel_restore_dlq_status_updated (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
