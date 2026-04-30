-- V11__drop_failed_kafka_event.sql
-- AFTER_COMMIT + failed_kafka_event 방식 제거 → TX3 인라인 Kafka 발행으로 전환
DROP TABLE IF EXISTS failed_kafka_event;
