-- STAFF-21 / HIST-11: the two tables docs/01_schema_mysql8.sql defines but no earlier migration created.
-- NOTI-23: EXPIRED reservations, which release the Case and the staff time like CANCELED does.
-- Adds tables and widens one CHECK; no existing row is changed.

CREATE TABLE clarification (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  review_item_id BIGINT UNSIGNED NULL,
  requested_revision INT UNSIGNED NOT NULL,
  kind VARCHAR(28) NOT NULL,
  question TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_by_run_id BIGINT UNSIGNED NULL,
  due_at DATETIME(6) NULL,
  answer_text TEXT NULL,
  confirmed_at DATETIME(6) NULL,
  answer_file_id BIGINT UNSIGNED NULL,
  result_revision INT UNSIGNED NULL,
  nonresponse_visit_assumption VARCHAR(28) NULL COMMENT 'UNCONFIRMED_ABSENT only, legal determination forbidden',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_clarification_case (case_id, status),
  CONSTRAINT fk_clarification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_clarification_item FOREIGN KEY (review_item_id) REFERENCES review_item(id),
  CONSTRAINT fk_clarification_run FOREIGN KEY (created_by_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_clarification_file FOREIGN KEY (answer_file_id) REFERENCES document_file(id),
  CONSTRAINT ck_clarification_status CHECK (status IN ('OPEN','ANSWERED','EXPIRED','ESCALATED')),
  CONSTRAINT ck_clarification_assumption CHECK (nonresponse_visit_assumption IS NULL OR nonresponse_visit_assumption='UNCONFIRMED_ABSENT')
) ENGINE=InnoDB;

CREATE TABLE case_statement (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_message_id BIGINT UNSIGNED NULL COMMENT '값만 보관. chat_message FK 금지',
  summary_text TEXT NOT NULL COMMENT '업무 관련 최소 내용. 30일 뒤 삭제',
  confirmed_by_customer BOOLEAN NOT NULL DEFAULT FALSE,
  source_at DATETIME(6) NULL,
  captured_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  KEY idx_statement_case (case_id),
  KEY idx_statement_expiry (expires_at),
  CONSTRAINT fk_statement_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT ck_statement_source CHECK (source_type IN ('CHAT','FORM','FREE_TEXT'))
) ENGINE=InnoDB;

ALTER TABLE visit_reservation DROP CHECK ck_reservation_status;
ALTER TABLE visit_reservation
  ADD CONSTRAINT ck_reservation_status CHECK (status IN ('SELECTED','PENDING_ASSIGNMENT','CONFIRMED','NEEDS_MANUAL_ASSIGNMENT','CANCELED','EXPIRED')),
  MODIFY COLUMN active_case_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status NOT IN ('CANCELED','EXPIRED') THEN case_id ELSE NULL END) STORED,
  MODIFY COLUMN active_staff_slot VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN status NOT IN ('CANCELED','EXPIRED') AND assigned_staff_id IS NOT NULL THEN CONCAT(assigned_staff_id,'@',DATE_FORMAT(starts_at,'%Y-%m-%d %H:%i:%s.%f')) ELSE NULL END) STORED;
