CREATE INDEX idx_cancel_outbox_redrive_requested_poll
  ON cancel_outbox_redrive (status, requested_at, id);

CREATE INDEX idx_cancel_outbox_redrive_convergence_poll
  ON cancel_outbox_redrive (status, started_at, id);
