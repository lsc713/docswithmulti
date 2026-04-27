-- =============================================================
-- V2__insert_test_data.sql
-- 결제 취소 API 로컬 테스트용 데이터
--
-- payment-service V9__insert_test_data.sql 의 merchant_id=1001 과 맞춤
-- =============================================================

INSERT INTO merchant (id, merchant_key, name, status, cancel_period_days)
VALUES (1001, 'merchant_001', '테스트 가맹점', 'ACTIVE', 30);

INSERT INTO merchant_cancel_limit (merchant_id, daily_limit)
VALUES (1001, 10000000.00);
