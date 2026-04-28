-- V9__add_cancel_item_ids_and_pg_retry_count.sql
-- cancel_request: cancel_item_ids (JSON 취소 대상 아이템 목록) + pg_retry_count (PG 재시도 횟수) 추가

ALTER TABLE cancel_request
    ADD COLUMN cancel_item_ids JSON NOT NULL
        COMMENT '취소 대상 payment_item_id 목록 (e.g. [1,2,3])'
        AFTER cancel_amount,
    ADD COLUMN pg_retry_count INT NOT NULL DEFAULT 0
        COMMENT 'processing-recovery PG 취소 재시도 횟수'
        AFTER pg_pending_since;
