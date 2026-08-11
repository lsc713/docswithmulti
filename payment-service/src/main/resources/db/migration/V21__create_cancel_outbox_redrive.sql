CREATE TABLE cancel_outbox_redrive (
  id                       BIGINT       NOT NULL AUTO_INCREMENT,
  source_outbox_id         BIGINT       NOT NULL,
  status                   VARCHAR(32)  NOT NULL,
  failure_stage            VARCHAR(20)  NULL,
  requested_by             VARCHAR(255) NOT NULL,
  reason                   VARCHAR(500) NOT NULL,
  requested_at             DATETIME(6)  NOT NULL,
  started_at               DATETIME(6)  NULL,
  completed_at             DATETIME(6)  NULL,
  result                   JSON         NULL,
  last_error               VARCHAR(500) NULL,
  before_state             JSON         NULL,
  after_state              JSON         NULL,
  active_source_outbox_id  BIGINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('REQUESTED', 'REDRIVING') THEN source_outbox_id ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  KEY idx_cancel_outbox_redrive_source (source_outbox_id),
  UNIQUE KEY uk_cancel_outbox_redrive_active (active_source_outbox_id),
  CONSTRAINT fk_cancel_outbox_redrive_source
    FOREIGN KEY (source_outbox_id) REFERENCES cancel_event_outbox(id),
  CONSTRAINT chk_cancel_outbox_redrive_status CHECK (
    status IN ('REQUESTED', 'REDRIVING', 'RESOLVED',
               'RESOLVED_ALREADY_APPLIED', 'REJECTED', 'FAILED')
  ),
  CONSTRAINT chk_cancel_outbox_redrive_failure_stage CHECK (
    (status = 'FAILED' AND failure_stage IN ('PUBLISH', 'CONVERGENCE')) OR
    (status <> 'FAILED' AND failure_stage IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
