-- =============================================================
-- V9__insert_test_data.sql
-- 결제 취소 API 로컬 테스트용 데이터
--
-- payment  : id=1, payment_key='test_pay_001', status=COMPLETED
-- payment_item : id=1 (상의 30,000원), id=2 (하의 50,000원) — 모두 ACTIVE
--
-- 취소 API 호출 예시 (payment-service :8080):
--   POST /api/payments/test_pay_001/cancel
--   { "cancelPaymentItemIds": [1, 2], "cancelAmount": 80000, "cancelReason": "단순 변심" }
-- =============================================================

INSERT INTO payment (id, payment_key, merchant_id, user_id, pg_type,
                     total_amount, currency, cancel_period_days, status)
VALUES (1, 'test_pay_001', 1001, 9001, 'TOSS',
        80000.00, 'KRW', 30, 'COMPLETED');

INSERT INTO payment_item (id, payment_id, order_item_id, product_id, product_auto_id,
                          item_name, item_amount, status)
VALUES (1, 1, 101, 201, 301, '흰 셔츠 M', 30000.00, 'ACTIVE'),
       (2, 1, 102, 202, 302, '청바지 32', 50000.00, 'ACTIVE');
