-- V20__create_cancel_approval.sql
CREATE TABLE cancel_approval (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  payment_id         BIGINT       NOT NULL,
  payment_key        VARCHAR(64)  NOT NULL,
  requester_user_id  BIGINT       NOT NULL,
  reason             VARCHAR(500) NOT NULL,
  status             VARCHAR(20)  NOT NULL,
  decided_by_user_id BIGINT       NULL,
  decided_role       VARCHAR(20)  NULL,
  decision_reason    VARCHAR(500) NULL,
  cancel_request_id  BIGINT       NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cancel_approval_payment (payment_id),
  KEY idx_cancel_approval_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
