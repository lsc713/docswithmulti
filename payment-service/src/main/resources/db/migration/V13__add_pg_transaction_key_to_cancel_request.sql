-- D-01 정정: PG(Toss) 취소 transactionKey 저장 (감사 + 부분취소 동일금액 tiebreaker)
ALTER TABLE cancel_request ADD COLUMN pg_transaction_key VARCHAR(64) NULL;
