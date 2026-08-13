ALTER TABLE payment
    ADD COLUMN payment_request_id CHAR(36) NULL AFTER id,
    MODIFY payment_key VARCHAR(200) NULL;

UPDATE payment SET payment_request_id = UUID() WHERE payment_request_id IS NULL;

ALTER TABLE payment
    ADD COLUMN active_pending_order_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN order_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_payment_request_id (payment_request_id),
    ADD UNIQUE KEY uk_payment_active_pending_order (active_pending_order_id);

ALTER TABLE payment_event_outbox MODIFY payment_key VARCHAR(200) NOT NULL;
ALTER TABLE cancel_approval MODIFY payment_key VARCHAR(200) NOT NULL;
