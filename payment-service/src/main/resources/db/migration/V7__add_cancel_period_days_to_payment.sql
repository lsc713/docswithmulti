-- =============================================================
-- V7__add_cancel_period_days_to_payment.sql
-- 결제 시점 가맹점 취소 정책 스냅샷 추가
-- 가맹점 정책 변경 시 기존 결제건에 소급 적용 방지
-- =============================================================

ALTER TABLE payment
    ADD COLUMN cancel_period_days INT NOT NULL DEFAULT 90
    AFTER currency;