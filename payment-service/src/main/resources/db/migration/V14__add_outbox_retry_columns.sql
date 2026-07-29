-- V14__add_outbox_retry_columns.sql
-- cancel_event_outbox: poison 재시도 관리용 retry_count + last_error 추가.
-- status 값 규약 확장: PENDING | PUBLISHED | DEAD (컬럼 타입 변경 없음).
ALTER TABLE cancel_event_outbox
    ADD COLUMN retry_count INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error  VARCHAR(500) NULL;
