-- payment_item에 SKU 재고 예약 계약 컬럼 추가 (spec §4, D-P2-3/4).
-- sku_id: product_sku.id 느슨 참조(FK 없음 — 모듈 격리, CLAUDE.md). NULL 허용 = 기존 데이터 하위호환.
-- quantity: 예약 수량. 기존 행은 DEFAULT 1로 백필.
ALTER TABLE payment_item
    ADD COLUMN sku_id   BIGINT NULL,
    ADD COLUMN quantity INT    NOT NULL DEFAULT 1;
