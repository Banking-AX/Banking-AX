-- V1 may already be applied. Persist the submit operation key separately from the Case state.
ALTER TABLE service_case
  ADD COLUMN submit_request_id VARCHAR(90) NULL,
  ADD UNIQUE KEY uk_case_submit_request (submit_request_id);
