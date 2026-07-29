-- V15__add_cancel_idempotency_key.sql
-- 클라 Idempotency-Key(optional) + content-hash fallback. dedup_key(generated) UK로 교체.
ALTER TABLE cancel_request
    ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER request_hash,
    ADD COLUMN dedup_key VARCHAR(300)
        AS (CONCAT(CASE WHEN idempotency_key IS NOT NULL THEN 'ik:' ELSE 'ch:' END,
                   COALESCE(idempotency_key, request_hash))) STORED,
    DROP KEY uk_cancel_request_hash,
    ADD UNIQUE KEY uk_cancel_request_dedup (payment_id, dedup_key);
