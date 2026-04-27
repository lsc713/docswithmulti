-- order_service API 생성을 위한 컬럼 추가
-- V2 정적 데이터 제거 후 API로 생성하는 방식으로 전환

ALTER TABLE orders
    ADD COLUMN user_id BIGINT NULL AFTER id;

ALTER TABLE order_item
    ADD COLUMN product_id BIGINT      NULL AFTER order_id,
    ADD COLUMN item_name  VARCHAR(100) NULL AFTER product_id,
    ADD COLUMN price      DECIMAL(19,2) NULL AFTER item_name;
