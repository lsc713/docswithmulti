-- PLINK-02: payment.order_id 강한 링크 (NOT NULL). order-service items:verify가 반환한
-- 검증된 orderId만 저장된다(클라 입력 아님, CreatePaymentService 최전방 검증 후 확정).
-- 직행 NOT NULL(spec §7 option a): 검증 표면은 Testcontainers(신규 MySQL, V1~V18 empty 테이블)이고
-- 과거 payment 행은 order 미상이라 backfill 불가 — dev/local에 기존 행이 있으면 dev DB를 재생성한다.
ALTER TABLE payment
    ADD COLUMN order_id BIGINT NOT NULL,
    ADD INDEX idx_payment_order_id (order_id);
