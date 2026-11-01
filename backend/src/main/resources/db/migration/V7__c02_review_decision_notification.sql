-- C-02 persisted staff-review path.  This is additive to V1-V6 and mirrors
-- the canonical schema; it deliberately excludes OCR and visit reservation.

INSERT IGNORE INTO document_type
  (code, name, submission_kind, per_person, source_kind, source_verified, ocr_enabled, is_active)
VALUES
  ('DECEDENT_DETAILED_FAMILY_RELATION', 'Decedent detailed family relation certificate', 'UPLOAD', FALSE, 'OTHER', FALSE, FALSE, TRUE),
  ('DECEDENT_BASIC_CERTIFICATE', 'Decedent basic certificate', 'UPLOAD', FALSE, 'OTHER', FALSE, FALSE, TRUE),
  ('PASSBOOK_AND_SEAL', 'Passbook and seal', 'VISIT_ONLY', FALSE, 'OTHER', FALSE, FALSE, TRUE),
  ('DECEDENT_REMOVED_FAMILY_REGISTER', 'Decedent removed family register', 'UPLOAD', FALSE, 'OTHER', FALSE, FALSE, TRUE),
  ('HEIR_IDENTITY_CARD', 'Heir identity card', 'UPLOAD', TRUE, 'OTHER', FALSE, FALSE, TRUE),
  ('HEIR_SEAL_OR_SIGNATURE', 'Heir seal or signature', 'VISIT_ONLY', TRUE, 'OTHER', FALSE, FALSE, TRUE),
  ('ABSENT_HEIR_ID_COPY', 'Absent heir identity copy', 'UPLOAD', TRUE, 'OTHER', FALSE, FALSE, TRUE),
  ('ABSENT_HEIR_SEAL_CERTIFICATE', 'Absent heir seal certificate', 'UPLOAD', TRUE, 'OTHER', FALSE, FALSE, TRUE),
  ('ABSENT_HEIR_POWER_OF_ATTORNEY', 'Absent heir power of attorney', 'UPLOAD', TRUE, 'OTHER', FALSE, FALSE, TRUE),
  ('DELEGATION_RECEIPT_NO', 'Delegation receipt number', 'INPUT_VALUE', TRUE, 'OTHER', FALSE, FALSE, TRUE);

CREATE TABLE agent_review_run (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  check_type VARCHAR(30) NOT NULL DEFAULT 'STAFF_PRECHECK',
  status VARCHAR(20) NOT NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  model_version VARCHAR(90) NULL,
  prompt_version VARCHAR(60) NULL,
  rule_version VARCHAR(40) NULL,
  failure_reason VARCHAR(255) NULL,
  report JSON NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  UNIQUE KEY uk_reviewrun_revision (case_id, revision, check_type),
  CONSTRAINT fk_reviewrun_revision FOREIGN KEY (case_id, revision) REFERENCES case_revision(case_id, revision),
  CONSTRAINT ck_reviewrun_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB;

CREATE TABLE review_item (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  agent_review_run_id BIGINT UNSIGNED NULL,
  source VARCHAR(30) NOT NULL,
  category VARCHAR(40) NOT NULL,
  result VARCHAR(36) NOT NULL,
  rule_id VARCHAR(70) NULL,
  rule_version VARCHAR(40) NULL,
  basis_doc_id VARCHAR(60) NULL,
  document_file_id BIGINT UNSIGNED NULL,
  heir_id BIGINT UNSIGNED NULL,
  message TEXT NULL,
  evidence JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_reviewitem_case_revision (case_id, revision),
  KEY idx_reviewitem_run (agent_review_run_id),
  CONSTRAINT fk_reviewitem_revision FOREIGN KEY (case_id, revision) REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_reviewitem_run FOREIGN KEY (agent_review_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_reviewitem_file FOREIGN KEY (document_file_id) REFERENCES document_file(id),
  CONSTRAINT fk_reviewitem_heir FOREIGN KEY (heir_id) REFERENCES heir(id),
  CONSTRAINT ck_reviewitem_source CHECK (source IN ('SPRING_RULE','AGENT_SEMANTIC','PRE_SUBMISSION'))
) ENGINE=InnoDB;

CREATE TABLE staff_review (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  staff_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  started_at DATETIME(6) NOT NULL,
  decided_at DATETIME(6) NULL,
  result VARCHAR(32) NULL,
  opinion_draft TEXT NULL,
  opinion_final TEXT NULL,
  follow_up_final TEXT NULL,
  adjustment JSON NULL,
  version INT NOT NULL DEFAULT 0,
  final_case_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN decided_at IS NOT NULL THEN case_id ELSE NULL END) STORED,
  UNIQUE KEY uk_staffreview_one_final (final_case_id),
  KEY idx_staffreview_staff (staff_id, started_at),
  KEY idx_staffreview_case (case_id, started_at),
  CONSTRAINT fk_staffreview_revision FOREIGN KEY (case_id, revision) REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_staffreview_staff FOREIGN KEY (staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_staffreview_result CHECK (result IS NULL OR result IN ('PROCEED','REQUIREMENT_FAILED','DOCUMENT_INSUFFICIENT')),
  CONSTRAINT ck_staffreview_final CHECK (decided_at IS NULL OR result IS NOT NULL)
) ENGINE=InnoDB;

CREATE TABLE notification (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  case_id BIGINT UNSIGNED NULL,
  work_item_id BIGINT UNSIGNED NULL,
  task_result_id BIGINT UNSIGNED NULL,
  type VARCHAR(60) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content TEXT NOT NULL,
  dedup_key VARCHAR(160) NOT NULL,
  read_at DATETIME(6) NULL,
  email_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
  email_attempts INT UNSIGNED NOT NULL DEFAULT 0,
  email_last_error VARCHAR(255) NULL,
  email_sent_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_notification_dedup (dedup_key),
  KEY idx_notification_customer (customer_id, created_at),
  CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT fk_notification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_notification_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_notification_target CHECK (case_id IS NOT NULL OR work_item_id IS NOT NULL),
  CONSTRAINT ck_notification_email CHECK (email_status IN ('NOT_REQUIRED','PENDING','SENDING','SENT','FAILED'))
) ENGINE=InnoDB;

CREATE TABLE event_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  entity_type VARCHAR(60) NOT NULL,
  entity_id VARCHAR(90) NOT NULL,
  event_type VARCHAR(90) NOT NULL,
  from_value VARCHAR(120) NULL,
  to_value VARCHAR(120) NULL,
  actor_type VARCHAR(24) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  request_id VARCHAR(90) NULL,
  payload JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_event_operation (request_id),
  KEY idx_event_entity (entity_type, entity_id, created_at),
  CONSTRAINT ck_event_actor CHECK (actor_type IN ('CUSTOMER','STAFF','AGENT','SYSTEM','ADMIN'))
) ENGINE=InnoDB;
