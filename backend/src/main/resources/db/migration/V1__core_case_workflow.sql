-- T1 core persistence subset. Types, status codes, FK shape, and constraints mirror docs/01_schema_mysql8.sql.
CREATE TABLE branch (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  branch_code VARCHAR(32) NOT NULL, name VARCHAR(100) NOT NULL, address VARCHAR(255) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE, operating_policy JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE KEY uk_branch_code (branch_code)
) ENGINE=InnoDB;

CREATE TABLE `user` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  login_id VARCHAR(80) NOT NULL, password_hash VARCHAR(255) NOT NULL, `role` VARCHAR(20) NOT NULL,
  name VARCHAR(100) NOT NULL, birth_date DATE NULL, phone VARCHAR(30) NULL, email VARCHAR(255) NULL, address VARCHAR(255) NULL,
  branch_id BIGINT UNSIGNED NULL, can_pre_review BOOLEAN NOT NULL DEFAULT FALSE, is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_login (login_id), KEY idx_user_branch_role (branch_id, `role`, is_active),
  CONSTRAINT fk_user_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
  CONSTRAINT ck_user_role CHECK (`role` IN ('CUSTOMER','STAFF','ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE chat_room (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), last_activity_at DATETIME(6) NULL, oldest_retained_at DATETIME(6) NULL,
  KEY idx_chatroom_customer (customer_id, last_activity_at), CONSTRAINT fk_chatroom_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) ENGINE=InnoDB;

CREATE TABLE workflow (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, chat_room_id BIGINT UNSIGNED NULL, customer_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(24) NOT NULL, version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_workflow_customer_status (customer_id, status, updated_at),
  CONSTRAINT fk_workflow_chatroom FOREIGN KEY (chat_room_id) REFERENCES chat_room(id),
  CONSTRAINT fk_workflow_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_workflow_status CHECK (status IN ('RUNNING','WAITING_CONTINUE','PAUSED','COMPLETED'))
) ENGINE=InnoDB;

CREATE TABLE work_item (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, workflow_id BIGINT UNSIGNED NOT NULL, task_code VARCHAR(40) NOT NULL,
  sequence_no INT UNSIGNED NOT NULL, status VARCHAR(20) NOT NULL, resume_step VARCHAR(80) NULL, draft_data JSON NULL,
  failure_reason VARCHAR(255) NULL, cancel_reason VARCHAR(60) NULL, request_id VARCHAR(90) NOT NULL,
  last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), reset_at DATETIME(6) NULL, version INT NOT NULL DEFAULT 0,
  active_workflow_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN workflow_id ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE KEY uk_workitem_request (request_id),
  UNIQUE KEY uk_workitem_sequence (workflow_id, sequence_no), UNIQUE KEY uk_workitem_active (active_workflow_id),
  KEY idx_workitem_idle (status, last_activity_at), KEY idx_workitem_workflow (workflow_id, status),
  CONSTRAINT fk_workitem_workflow FOREIGN KEY (workflow_id) REFERENCES workflow(id),
  CONSTRAINT ck_workitem_task CHECK (task_code IN ('TRANSACTION_SEARCH','LOAN_INQUIRY','REPAYMENT_ORDER_CHANGE','HOUSING_SAVINGS_CLOSE','DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT')),
  CONSTRAINT ck_workitem_status CHECK (status IN ('PLANNED','ACTIVE','ON_HOLD','DONE','HANDED_OFF','FAILED','CANCELED'))
) ENGINE=InnoDB;

CREATE TABLE loan (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL, loan_no VARCHAR(40) NOT NULL,
  loan_name VARCHAR(120) NULL, principal_amount DECIMAL(18,2) NOT NULL, balance DECIMAL(18,2) NOT NULL,
  interest_rate DECIMAL(7,4) NULL, maturity_date DATE NULL, overdue_days INT UNSIGNED NOT NULL DEFAULT 0,
  acceleration_declared BOOLEAN NOT NULL DEFAULT FALSE, acceleration_date DATE NULL, repayment_order VARCHAR(24) NOT NULL DEFAULT 'INTEREST_FIRST',
  outsourced BOOLEAN NOT NULL DEFAULT FALSE, agreement_loan BOOLEAN NOT NULL DEFAULT FALSE, debt_adjust_terminated_at DATE NULL,
  prev_adjustment_note VARCHAR(255) NULL, in_litigation BOOLEAN NOT NULL DEFAULT FALSE, other_procedure BOOLEAN NOT NULL DEFAULT FALSE,
  prescription_completed BOOLEAN NOT NULL DEFAULT FALSE, has_third_party_provider BOOLEAN NOT NULL DEFAULT FALSE, version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_loan_no (loan_no), KEY idx_loan_customer (customer_id), CONSTRAINT fk_loan_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_repayment_order CHECK (repayment_order IN ('INTEREST_FIRST','PRINCIPAL_FIRST'))
) ENGINE=InnoDB;

CREATE TABLE decedent (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, birth_date DATE NULL, death_date DATE NOT NULL,
  deposit_total DECIMAL(18,2) NOT NULL DEFAULT 0, deposits JSON NULL
) ENGINE=InnoDB;

CREATE TABLE service_case (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, work_item_id BIGINT UNSIGNED NOT NULL, customer_id BIGINT UNSIGNED NOT NULL,
  case_type VARCHAR(40) NOT NULL, case_status VARCHAR(36) NOT NULL DEFAULT 'DRAFT', review_status VARCHAR(36) NULL,
  revision INT UNSIGNED NOT NULL DEFAULT 0, previous_case_id BIGINT UNSIGNED NULL, loan_id BIGINT UNSIGNED NULL, decedent_id BIGINT UNSIGNED NULL,
  visit_required BOOLEAN NULL, application JSON NOT NULL, assigned_staff_id BIGINT UNSIGNED NULL,
  submitted_at DATETIME(6) NULL, review_started_at DATETIME(6) NULL, decided_at DATETIME(6) NULL, result_notified_at DATETIME(6) NULL, canceled_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_case_workitem (work_item_id), KEY idx_case_customer_status (customer_id, case_status, created_at), KEY idx_case_reviewqueue (case_status, review_status, submitted_at),
  KEY idx_case_assigned (assigned_staff_id, case_status), CONSTRAINT fk_case_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_case_customer FOREIGN KEY (customer_id) REFERENCES `user`(id), CONSTRAINT fk_case_prev FOREIGN KEY (previous_case_id) REFERENCES service_case(id),
  CONSTRAINT fk_case_loan FOREIGN KEY (loan_id) REFERENCES loan(id), CONSTRAINT fk_case_decedent FOREIGN KEY (decedent_id) REFERENCES decedent(id),
  CONSTRAINT fk_case_assigned FOREIGN KEY (assigned_staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_case_type CHECK (case_type IN ('DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT')),
  CONSTRAINT ck_case_status CHECK (case_status IN ('DRAFT','SUBMITTED','IN_REVIEW','REVIEW_PASSED','REVIEW_REJECTED_REQUIREMENT','REVIEW_REJECTED_DOCUMENT','CANCELED')),
  CONSTRAINT ck_case_review_status CHECK (review_status IS NULL OR review_status IN ('PENDING','RUNNING','WAITING_CUSTOMER','READY_FOR_STAFF','FAILED_NEEDS_STAFF')),
  CONSTRAINT ck_case_debt_link CHECK ((case_type='DEBT_ADJUSTMENT' AND (case_status IN ('DRAFT','CANCELED') OR loan_id IS NOT NULL)) OR (case_type='INHERITANCE_DEPOSIT' AND loan_id IS NULL)),
  CONSTRAINT ck_case_visit_required CHECK (case_type <> 'INHERITANCE_DEPOSIT' OR (visit_required IS NOT NULL AND visit_required = TRUE))
) ENGINE=InnoDB;

CREATE TABLE case_revision (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, case_id BIGINT UNSIGNED NOT NULL, revision INT UNSIGNED NOT NULL,
  application_snapshot JSON NOT NULL, heir_snapshot JSON NULL, document_manifest JSON NULL, change_reason VARCHAR(60) NOT NULL,
  created_by_user_id BIGINT UNSIGNED NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_case_revision (case_id, revision), KEY idx_revision_created (case_id, created_at),
  CONSTRAINT fk_revision_case FOREIGN KEY (case_id) REFERENCES service_case(id), CONSTRAINT fk_revision_actor FOREIGN KEY (created_by_user_id) REFERENCES `user`(id)
) ENGINE=InnoDB;
