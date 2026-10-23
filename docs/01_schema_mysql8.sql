-- 신한 SW 팀 프로젝트: 기능명세서 v1.7 기준 논리/물리 ERD DDL
-- 대상: MySQL 8.0.16+, InnoDB, UTF8MB4, DATETIME(6)은 UTC 저장·표시는 KST 변환
-- PoC 신규 DB 생성용. 실제 은행 운영 정책/법적 판단을 구현하는 스키마가 아님.
CREATE DATABASE IF NOT EXISTS shinhan_agent_poc
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE shinhan_agent_poc;

-- 01. 영업점 / 사용자 / 직원 업무 코드
CREATE TABLE branch (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  branch_code VARCHAR(32) NOT NULL,
  name VARCHAR(100) NOT NULL,
  address VARCHAR(255) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  operating_policy JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_branch_code (branch_code)
) ENGINE=InnoDB;

CREATE TABLE `user` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  login_id VARCHAR(80) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  `role` VARCHAR(20) NOT NULL COMMENT 'CUSTOMER, STAFF, ADMIN',
  name VARCHAR(100) NOT NULL,
  birth_date DATE NULL,
  phone VARCHAR(30) NULL,
  email VARCHAR(255) NULL,
  address VARCHAR(255) NULL,
  branch_id BIGINT UNSIGNED NULL COMMENT '직원 소속 지점. 예약 전 사전검토 직원은 null 허용',
  can_pre_review BOOLEAN NOT NULL DEFAULT FALSE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_login (login_id),
  KEY idx_user_branch_role (branch_id, `role`, is_active),
  CONSTRAINT fk_user_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
  CONSTRAINT ck_user_role CHECK (`role` IN ('CUSTOMER','STAFF','ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE staff_task_capability (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  staff_id BIGINT UNSIGNED NOT NULL,
  task_code VARCHAR(40) NOT NULL COMMENT 'DEBT_ADJUSTMENT, INHERITANCE_DEPOSIT',
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_staff_task (staff_id, task_code),
  KEY idx_capability_task (task_code, is_enabled),
  CONSTRAINT fk_cap_staff FOREIGN KEY (staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_cap_task CHECK (task_code IN ('DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT'))
) ENGINE=InnoDB;

-- 02. 채팅 (7일) / 워크플로우 / 미제출 임시 데이터 (72시간)
CREATE TABLE chat_room (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_activity_at DATETIME(6) NULL,
  oldest_retained_at DATETIME(6) NULL,
  KEY idx_chatroom_customer (customer_id, last_activity_at),
  CONSTRAINT fk_chatroom_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) ENGINE=InnoDB;

CREATE TABLE chat_message (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  chat_room_id BIGINT UNSIGNED NOT NULL,
  seq INT UNSIGNED NOT NULL,
  sender_type VARCHAR(16) NOT NULL COMMENT 'CUSTOMER, AGENT, SYSTEM',
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SENT',
  citations JSON NULL,
  sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_chatmessage_seq (chat_room_id, seq),
  KEY idx_chatmessage_expiry (sent_at),
  CONSTRAINT fk_chatmessage_room FOREIGN KEY (chat_room_id) REFERENCES chat_room(id),
  CONSTRAINT ck_chatmessage_sender CHECK (sender_type IN ('CUSTOMER','AGENT','SYSTEM'))
) ENGINE=InnoDB;

CREATE TABLE workflow (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  chat_room_id BIGINT UNSIGNED NULL COMMENT '재신청 등 비채팅 진입 허용',
  customer_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(24) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_workflow_customer_status (customer_id, status, updated_at),
  CONSTRAINT fk_workflow_chatroom FOREIGN KEY (chat_room_id) REFERENCES chat_room(id),
  CONSTRAINT fk_workflow_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_workflow_status CHECK (status IN ('RUNNING','WAITING_CONTINUE','PAUSED','COMPLETED'))
) ENGINE=InnoDB;

CREATE TABLE work_item (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  workflow_id BIGINT UNSIGNED NOT NULL,
  task_code VARCHAR(40) NOT NULL,
  sequence_no INT UNSIGNED NOT NULL,
  status VARCHAR(20) NOT NULL,
  resume_step VARCHAR(80) NULL,
  draft_data JSON NULL,
  failure_reason VARCHAR(255) NULL,
  cancel_reason VARCHAR(60) NULL,
  request_id VARCHAR(90) NOT NULL,
  last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reset_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  -- MySQL UNIQUE 에서 NULL은 중복 허용: ACTIVE 행에서만 workflow당 유일 강제
  active_workflow_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE' THEN workflow_id ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_workitem_request (request_id),
  UNIQUE KEY uk_workitem_sequence (workflow_id, sequence_no),
  UNIQUE KEY uk_workitem_active (active_workflow_id),
  KEY idx_workitem_idle (status, last_activity_at),
  KEY idx_workitem_workflow (workflow_id, status),
  CONSTRAINT fk_workitem_workflow FOREIGN KEY (workflow_id) REFERENCES workflow(id),
  CONSTRAINT ck_workitem_task CHECK (task_code IN
    ('TRANSACTION_SEARCH','LOAN_INQUIRY','REPAYMENT_ORDER_CHANGE',
     'HOUSING_SAVINGS_CLOSE','DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT')),
  CONSTRAINT ck_workitem_status CHECK (status IN
    ('PLANNED','ACTIVE','ON_HOLD','DONE','HANDED_OFF','FAILED','CANCELED'))
) ENGINE=InnoDB;

CREATE TABLE task_result (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT UNSIGNED NOT NULL,
  result_type VARCHAR(40) NOT NULL,
  decision_code VARCHAR(60) NULL,
  target_loan_id BIGINT UNSIGNED NULL COMMENT '필요 시 검색 가능한 대상 식별자; FK는 loan 뒤에 설정하지 않음',
  rule_version VARCHAR(40) NULL,
  basis_doc_id VARCHAR(60) NULL,
  before_value JSON NULL,
  after_value JSON NULL,
  detail JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_taskresult_workitem (work_item_id, created_at),
  CONSTRAINT fk_taskresult_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id)
) ENGINE=InnoDB;

CREATE TABLE consent_record (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT UNSIGNED NOT NULL,
  consent_type VARCHAR(60) NOT NULL,
  notice_version VARCHAR(40) NOT NULL,
  agreed BOOLEAN NOT NULL,
  agreed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_consent_workitem (work_item_id, agreed_at),
  CONSTRAINT fk_consent_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id)
) ENGINE=InnoDB;

-- 03. 모의 금융 데이터: 실계좌 / 법적 효력 없음
CREATE TABLE deposit_account (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  account_no VARCHAR(40) NOT NULL,
  account_name VARCHAR(120) NOT NULL,
  balance DECIMAL(18,2) NOT NULL DEFAULT 0,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_deposit_account_no (account_no),
  KEY idx_deposit_customer (customer_id),
  CONSTRAINT fk_deposit_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) ENGINE=InnoDB;

CREATE TABLE account_transaction (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT UNSIGNED NOT NULL,
  tx_at DATETIME(6) NOT NULL,
  direction VARCHAR(10) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  counterparty VARCHAR(120) NULL,
  memo VARCHAR(255) NULL,
  KEY idx_transaction_search (account_id, tx_at, id),
  CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES deposit_account(id),
  CONSTRAINT ck_transaction_direction CHECK (direction IN ('IN','OUT')),
  CONSTRAINT ck_transaction_amount CHECK (amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE loan (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  loan_no VARCHAR(40) NOT NULL,
  loan_name VARCHAR(120) NULL,
  principal_amount DECIMAL(18,2) NOT NULL,
  balance DECIMAL(18,2) NOT NULL,
  interest_rate DECIMAL(7,4) NULL,
  maturity_date DATE NULL,
  overdue_days INT UNSIGNED NOT NULL DEFAULT 0,
  acceleration_declared BOOLEAN NOT NULL DEFAULT FALSE,
  acceleration_date DATE NULL,
  repayment_order VARCHAR(24) NOT NULL DEFAULT 'INTEREST_FIRST',
  outsourced BOOLEAN NOT NULL DEFAULT FALSE,
  agreement_loan BOOLEAN NOT NULL DEFAULT FALSE,
  debt_adjust_terminated_at DATE NULL,
  prev_adjustment_note VARCHAR(255) NULL,
  in_litigation BOOLEAN NOT NULL DEFAULT FALSE,
  other_procedure BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Mock 고객 진술; 직원 최종 확인',
  prescription_completed BOOLEAN NOT NULL DEFAULT FALSE,
  has_third_party_provider BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'PoC B-01 비대면 가정 허용',
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_loan_no (loan_no),
  KEY idx_loan_customer (customer_id),
  CONSTRAINT fk_loan_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_repayment_order CHECK (repayment_order IN ('INTEREST_FIRST','PRINCIPAL_FIRST'))
) ENGINE=InnoDB;

CREATE TABLE housing_savings_account (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  account_no VARCHAR(40) NOT NULL,
  joined_at DATE NOT NULL,
  paid_principal DECIMAL(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  subscription_won BOOLEAN NOT NULL DEFAULT FALSE,
  converted BOOLEAN NOT NULL DEFAULT FALSE,
  applied_rate_type VARCHAR(32) NULL,
  tax_mock_data JSON NULL COMMENT 'B-02 Mock 과세내역; 실제 OCR 미적용',
  closed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_housing_account_no (account_no),
  KEY idx_housing_customer (customer_id),
  CONSTRAINT fk_housing_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_housing_status CHECK (status IN ('ACTIVE','CLOSED'))
) ENGINE=InnoDB;

CREATE TABLE decedent (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  birth_date DATE NULL,
  death_date DATE NOT NULL,
  deposit_total DECIMAL(18,2) NOT NULL DEFAULT 0,
  deposits JSON NULL COMMENT '모의 피상속인 계좌 목록: 조회 전용'
) ENGINE=InnoDB;

CREATE TABLE delegation_receipt (
  receipt_no VARCHAR(80) PRIMARY KEY,
  decedent_id BIGINT UNSIGNED NOT NULL,
  delegator_name VARCHAR(100) NOT NULL,
  agent_name VARCHAR(100) NOT NULL,
  is_valid BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_delegation_decedent (decedent_id),
  CONSTRAINT fk_delegation_decedent FOREIGN KEY (decedent_id) REFERENCES decedent(id)
) ENGINE=InnoDB;

-- 04. C업무: Case / 고객별 수정버전 / 상속인 / 30일 요약
CREATE TABLE service_case (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT UNSIGNED NOT NULL,
  customer_id BIGINT UNSIGNED NOT NULL,
  case_type VARCHAR(40) NOT NULL,
  case_status VARCHAR(36) NOT NULL DEFAULT 'DRAFT',
  review_status VARCHAR(36) NULL,
  revision INT UNSIGNED NOT NULL DEFAULT 0,
  previous_case_id BIGINT UNSIGNED NULL,
  loan_id BIGINT UNSIGNED NULL,
  decedent_id BIGINT UNSIGNED NULL,
  visit_required BOOLEAN NULL COMMENT 'C02=true; C01 직원 검토 후 결정. 예약과는 별개',
  application JSON NOT NULL COMMENT '현재 C신청 정보. 상속인 배열은 heir 테이블 사용',
  assigned_staff_id BIGINT UNSIGNED NULL COMMENT '방문 예약 확정 직원만(중복값); 예약 전 검토 직원 아님',
  submitted_at DATETIME(6) NULL,
  review_started_at DATETIME(6) NULL,
  decided_at DATETIME(6) NULL,
  result_notified_at DATETIME(6) NULL COMMENT '검토 결과 웹 알림 DB 생성 시각 (SMTP 도달 보장 아님)',
  canceled_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_case_workitem (work_item_id),
  KEY idx_case_customer_status (customer_id, case_status, created_at),
  KEY idx_case_reviewqueue (case_status, review_status, submitted_at),
  KEY idx_case_assigned (assigned_staff_id, case_status),
  CONSTRAINT fk_case_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_case_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT fk_case_prev FOREIGN KEY (previous_case_id) REFERENCES service_case(id),
  CONSTRAINT fk_case_loan FOREIGN KEY (loan_id) REFERENCES loan(id),
  CONSTRAINT fk_case_decedent FOREIGN KEY (decedent_id) REFERENCES decedent(id),
  CONSTRAINT fk_case_assigned FOREIGN KEY (assigned_staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_case_type CHECK (case_type IN ('DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT')),
  CONSTRAINT ck_case_status CHECK (case_status IN
    ('DRAFT','SUBMITTED','IN_REVIEW','REVIEW_PASSED',
     'REVIEW_REJECTED_REQUIREMENT','REVIEW_REJECTED_DOCUMENT','CANCELED')),
  CONSTRAINT ck_case_review_status CHECK (review_status IS NULL OR review_status IN
    ('PENDING','RUNNING','WAITING_CUSTOMER','READY_FOR_STAFF','FAILED_NEEDS_STAFF')),
  CONSTRAINT ck_case_debt_link CHECK (
    (case_type='DEBT_ADJUSTMENT' AND (case_status IN ('DRAFT','CANCELED') OR loan_id IS NOT NULL)) OR
    (case_type='INHERITANCE_DEPOSIT' AND loan_id IS NULL)),
  CONSTRAINT ck_case_visit_required CHECK (
    case_type <> 'INHERITANCE_DEPOSIT' OR (visit_required IS NOT NULL AND visit_required = TRUE))
) ENGINE=InnoDB;

CREATE TABLE heir (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  person_ref VARCHAR(40) NOT NULL COMMENT 'Case 안에서 안정적인 사람 참조값',
  name VARCHAR(100) NOT NULL,
  birth_date DATE NULL,
  relationship VARCHAR(80) NOT NULL,
  is_applicant BOOLEAN NOT NULL DEFAULT FALSE,
  applicant_case_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN is_applicant=TRUE THEN case_id ELSE NULL END) STORED,
  visit_status VARCHAR(28) NOT NULL DEFAULT 'UNKNOWN',
  delegation_method VARCHAR(28) NULL COMMENT 'NONE, DOCUMENT, RECEIPT',
  delegation_receipt_no VARCHAR(80) NULL,
  customer_confirmed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_heir_person (case_id, person_ref),
  UNIQUE KEY uk_heir_one_applicant (applicant_case_id),
  KEY idx_heir_visit (case_id, visit_status),
  CONSTRAINT fk_heir_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_heir_receipt FOREIGN KEY (delegation_receipt_no) REFERENCES delegation_receipt(receipt_no),
  CONSTRAINT ck_heir_visit CHECK (visit_status IN
    ('UNKNOWN','ATTENDING','ABSENT','UNCONFIRMED_ABSENT')),
  CONSTRAINT ck_heir_delegation CHECK (delegation_method IS NULL OR delegation_method IN
    ('NONE','DOCUMENT','RECEIPT'))
) ENGINE=InnoDB;

CREATE TABLE case_revision (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  application_snapshot JSON NOT NULL COMMENT '원본 업무 신청 스냅샷: 30일 진술 요약 복제 금지',
  heir_snapshot JSON NULL COMMENT '해당 버전의 상속인/방문·위임 입력값',
  document_manifest JSON NULL COMMENT '해당 버전에 유효한 document_file ID/버전 목록',
  change_reason VARCHAR(60) NOT NULL,
  created_by_user_id BIGINT UNSIGNED NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_case_revision (case_id, revision),
  KEY idx_revision_created (case_id, created_at),
  CONSTRAINT fk_revision_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_revision_actor FOREIGN KEY (created_by_user_id) REFERENCES `user`(id)
) ENGINE=InnoDB;

CREATE TABLE case_statement (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_message_id BIGINT UNSIGNED NULL COMMENT '값만 보관. chat_message FK 금지',
  summary_text TEXT NOT NULL COMMENT '업무 관련 AI 최소 요약. 원문 발췌 영구보관 금지',
  confirmed_by_customer BOOLEAN NOT NULL DEFAULT FALSE,
  source_at DATETIME(6) NULL,
  captured_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  KEY idx_statement_ttl (expires_at),
  KEY idx_statement_case (case_id, revision),
  CONSTRAINT fk_statement_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT ck_statement_source CHECK (source_type IN ('CHAT','FORM','FREE_TEXT'))
) ENGINE=InnoDB;

-- 05. 제출서류: WorkItem 소유(B-02와 C 공통), 사람별 정확한 FK
CREATE TABLE document_type (
  code VARCHAR(60) PRIMARY KEY,
  name VARCHAR(140) NOT NULL,
  submission_kind VARCHAR(28) NOT NULL,
  valid_days INT UNSIGNED NULL,
  per_person BOOLEAN NOT NULL DEFAULT FALSE,
  source_kind VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT 'SHINHAN_OFFICIAL, OTHER',
  source_doc_id VARCHAR(60) NULL,
  source_verified BOOLEAN NOT NULL DEFAULT FALSE,
  ocr_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT ck_doctype_kind CHECK (submission_kind IN
    ('UPLOAD','VISIT_ONLY','SYSTEM_GENERATED','SCREEN_CONSENT','INPUT_VALUE')),
  CONSTRAINT ck_doctype_source CHECK (source_kind IN ('SHINHAN_OFFICIAL','OTHER')),
  CONSTRAINT ck_doctype_ocr CHECK
    (ocr_enabled = FALSE OR (source_kind='SHINHAN_OFFICIAL' AND source_verified=TRUE))
) ENGINE=InnoDB;

CREATE TABLE required_document (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT UNSIGNED NOT NULL,
  document_type_code VARCHAR(60) NOT NULL,
  heir_id BIGINT UNSIGNED NULL COMMENT 'C02 제출자별 서류. person_ref는 heir에서 조회',
  scope_heir_key BIGINT UNSIGNED GENERATED ALWAYS AS (COALESCE(heir_id, 0)) STORED,
  requirement VARCHAR(20) NOT NULL COMMENT 'REQUIRED, CONDITIONAL, VISIT_ONLY, OPTIONAL',
  condition_note VARCHAR(255) NULL,
  rule_version VARCHAR(40) NULL,
  status VARCHAR(26) NOT NULL DEFAULT 'NOT_SUBMITTED',
  human_checked_by BIGINT UNSIGNED NULL,
  human_checked_at DATETIME(6) NULL,
  human_check_note VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_required_workitem_type_person (work_item_id, document_type_code, scope_heir_key),
  KEY idx_required_workitem (work_item_id),
  KEY idx_required_heir (heir_id),
  CONSTRAINT fk_required_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_required_type FOREIGN KEY (document_type_code) REFERENCES document_type(code),
  CONSTRAINT fk_required_heir FOREIGN KEY (heir_id) REFERENCES heir(id),
  CONSTRAINT fk_required_checker FOREIGN KEY (human_checked_by) REFERENCES `user`(id),
  CONSTRAINT ck_required_requirement CHECK
    (requirement IN ('REQUIRED','CONDITIONAL','VISIT_ONLY','OPTIONAL')),
  CONSTRAINT ck_required_status CHECK (status IN
    ('NOT_SUBMITTED','UPLOADED','VALIDATION_FAILED','SYSTEM_VALIDATED',
     'HUMAN_VERIFIED','NEEDS_SUPPLEMENT'))
) ENGINE=InnoDB;

CREATE TABLE document_file (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  required_document_id BIGINT UNSIGNED NOT NULL,
  version INT UNSIGNED NOT NULL,
  is_latest BOOLEAN NOT NULL DEFAULT TRUE,
  original_name VARCHAR(255) NOT NULL,
  extension VARCHAR(12) NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  issued_date DATE NULL,
  submitter VARCHAR(100) NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  uploaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL COMMENT '미제출 72시간 자동 초기화의 물리파일 삭제 추적',
  latest_required_document_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN is_latest=TRUE THEN required_document_id ELSE NULL END) STORED,
  UNIQUE KEY uk_file_slot_version (required_document_id, version),
  UNIQUE KEY uk_file_one_latest (latest_required_document_id),
  KEY idx_file_uploaded (uploaded_at),
  CONSTRAINT fk_file_required FOREIGN KEY (required_document_id) REFERENCES required_document(id),
  CONSTRAINT fk_file_uploader FOREIGN KEY (uploaded_by) REFERENCES `user`(id),
  CONSTRAINT ck_file_size CHECK (size_bytes <= 10485760)
) ENGINE=InnoDB;

CREATE TABLE document_extraction (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  document_file_id BIGINT UNSIGNED NOT NULL,
  method VARCHAR(20) NOT NULL COMMENT 'NONE, TEXT_PARSER, CLOVA_OCR',
  status VARCHAR(28) NOT NULL,
  detected_type VARCHAR(60) NULL,
  raw_text_path VARCHAR(512) NULL,
  confidence DECIMAL(6,5) NULL,
  fields JSON NULL,
  model_version VARCHAR(90) NULL,
  prompt_version VARCHAR(60) NULL,
  failure_reason VARCHAR(255) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  KEY idx_extraction_file (document_file_id, started_at),
  CONSTRAINT fk_extraction_file FOREIGN KEY (document_file_id) REFERENCES document_file(id),
  CONSTRAINT ck_extraction_method CHECK (method IN ('NONE','TEXT_PARSER','CLOVA_OCR')),
  CONSTRAINT ck_extraction_status CHECK (status IN
    ('PENDING','RUNNING','SUCCEEDED','FAILED','UNREADABLE','NOT_APPLICABLE')),
  CONSTRAINT ck_extraction_skipped CHECK
    (method <> 'NONE' OR status='NOT_APPLICABLE')
) ENGINE=InnoDB;

-- 06. 직원 Agent / 정형검증 / 추가 확인 / 직원 확정
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
  CONSTRAINT fk_reviewrun_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision),
  CONSTRAINT ck_reviewrun_status CHECK
    (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB;

CREATE TABLE review_item (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  agent_review_run_id BIGINT UNSIGNED NULL,
  source VARCHAR(30) NOT NULL COMMENT 'SPRING_RULE, AGENT_SEMANTIC, PRE_SUBMISSION',
  category VARCHAR(40) NOT NULL,
  result VARCHAR(36) NOT NULL,
  rule_id VARCHAR(70) NULL,
  rule_version VARCHAR(40) NULL,
  basis_doc_id VARCHAR(60) NULL,
  document_file_id BIGINT UNSIGNED NULL,
  heir_id BIGINT UNSIGNED NULL,
  message TEXT NULL,
  evidence JSON NULL COMMENT '고객서류 원문 근거 / 업무규정 근거 분리',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_reviewitem_case_revision (case_id, revision),
  KEY idx_reviewitem_run (agent_review_run_id),
  CONSTRAINT fk_reviewitem_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_reviewitem_run FOREIGN KEY (agent_review_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_reviewitem_file FOREIGN KEY (document_file_id) REFERENCES document_file(id),
  CONSTRAINT fk_reviewitem_heir FOREIGN KEY (heir_id) REFERENCES heir(id),
  CONSTRAINT ck_reviewitem_source CHECK
    (source IN ('SPRING_RULE','AGENT_SEMANTIC','PRE_SUBMISSION'))
) ENGINE=InnoDB;

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
  KEY idx_clarification_case_status (case_id, status),
  CONSTRAINT fk_clarification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_clarification_item FOREIGN KEY (review_item_id) REFERENCES review_item(id),
  CONSTRAINT fk_clarification_run FOREIGN KEY (created_by_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_clarification_file FOREIGN KEY (answer_file_id) REFERENCES document_file(id),
  CONSTRAINT ck_clarification_status CHECK
    (status IN ('OPEN','ANSWERED','EXPIRED','ESCALATED')),
  CONSTRAINT ck_clarification_assumption CHECK
    (nonresponse_visit_assumption IS NULL OR
     nonresponse_visit_assumption='UNCONFIRMED_ABSENT')
) ENGINE=InnoDB;

CREATE TABLE staff_review (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  staff_id BIGINT UNSIGNED NOT NULL COMMENT '예약 전 사전검토 직원; 방문 담당 직원과 별개',
  revision INT UNSIGNED NOT NULL,
  started_at DATETIME(6) NOT NULL,
  decided_at DATETIME(6) NULL,
  result VARCHAR(32) NULL COMMENT 'PROCEED / REQUIREMENT_FAILED / DOCUMENT_INSUFFICIENT',
  opinion_draft TEXT NULL,
  opinion_final TEXT NULL,
  follow_up_final TEXT NULL,
  adjustment JSON NULL COMMENT 'C-01 모의 조정 내용',
  version INT NOT NULL DEFAULT 0,
  final_case_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN decided_at IS NOT NULL THEN case_id ELSE NULL END) STORED,
  UNIQUE KEY uk_staffreview_one_final (final_case_id),
  KEY idx_staffreview_staff (staff_id, started_at),
  KEY idx_staffreview_case (case_id, started_at),
  CONSTRAINT fk_staffreview_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_staffreview_staff FOREIGN KEY (staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_staffreview_result CHECK
    (result IS NULL OR result IN ('PROCEED','REQUIREMENT_FAILED','DOCUMENT_INSUFFICIENT')),
  CONSTRAINT ck_staffreview_final CHECK (decided_at IS NULL OR result IS NOT NULL)
) ENGINE=InnoDB;

-- 07. 알림: A 미발송, B/C 이메일; 타입별 업무 참조
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
  KEY idx_notification_email (email_status, created_at),
  CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT fk_notification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_notification_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_notification_result FOREIGN KEY (task_result_id) REFERENCES task_result(id),
  CONSTRAINT ck_notification_target CHECK (case_id IS NOT NULL OR work_item_id IS NOT NULL),
  CONSTRAINT ck_notification_email CHECK (email_status IN
    ('NOT_REQUIRED','PENDING','SENDING','SENT','FAILED'))
) ENGINE=InnoDB;

-- 08. 예약: 검토 결과 통지 후 고객 예약 시작; 동시성은 Spring 락 + DB 체크
CREATE TABLE branch_slot (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  branch_id BIGINT UNSIGNED NOT NULL,
  start_at DATETIME(6) NOT NULL,
  end_at DATETIME(6) NOT NULL,
  capacity INT UNSIGNED NOT NULL,
  reserved_count INT UNSIGNED NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_branchslot_time (branch_id, start_at, end_at),
  KEY idx_branchslot_available (branch_id, start_at, status),
  CONSTRAINT fk_branchslot_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
  CONSTRAINT ck_branchslot_capacity CHECK (capacity > 0 AND reserved_count <= capacity),
  CONSTRAINT ck_branchslot_period CHECK (end_at > start_at),
  CONSTRAINT ck_branchslot_status CHECK (status IN ('OPEN','CLOSED'))
) ENGINE=InnoDB;

CREATE TABLE staff_slot_availability (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  staff_id BIGINT UNSIGNED NOT NULL,
  branch_slot_id BIGINT UNSIGNED NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  max_assignments INT UNSIGNED NOT NULL DEFAULT 1,
  assigned_count INT UNSIGNED NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_staff_slot (staff_id, branch_slot_id),
  KEY idx_slot_staff_availability (branch_slot_id, available),
  CONSTRAINT fk_staffslot_staff FOREIGN KEY (staff_id) REFERENCES `user`(id),
  CONSTRAINT fk_staffslot_slot FOREIGN KEY (branch_slot_id) REFERENCES branch_slot(id),
  CONSTRAINT ck_staffslot_count CHECK
    (max_assignments > 0 AND assigned_count <= max_assignments)
) ENGINE=InnoDB;

CREATE TABLE visit_reservation (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  branch_slot_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SELECTED',
  assigned_staff_id BIGINT UNSIGNED NULL,
  staff_availability_id BIGINT UNSIGNED NULL,
  recommended_staff_id BIGINT UNSIGNED NULL,
  assignment_reason JSON NULL COMMENT '업무코드/업무량 근거만, 경험점수 제외',
  assignment_model_version VARCHAR(90) NULL,
  assignment_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  request_id VARCHAR(90) NOT NULL,
  selected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  confirmed_at DATETIME(6) NULL,
  canceled_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_reservation_case (case_id),
  UNIQUE KEY uk_reservation_create_request (request_id),
  KEY idx_reservation_slot_status (branch_slot_id, status),
  KEY idx_reservation_staff (assigned_staff_id, status),
  CONSTRAINT fk_reservation_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_reservation_slot FOREIGN KEY (branch_slot_id) REFERENCES branch_slot(id),
  CONSTRAINT fk_reservation_staff FOREIGN KEY (assigned_staff_id) REFERENCES `user`(id),
  CONSTRAINT fk_reservation_staffslot FOREIGN KEY (staff_availability_id) REFERENCES staff_slot_availability(id),
  CONSTRAINT fk_reservation_recommended FOREIGN KEY (recommended_staff_id) REFERENCES `user`(id),
  CONSTRAINT ck_reservation_status CHECK (status IN
    ('SELECTED','PENDING_ASSIGNMENT','CONFIRMED','NEEDS_MANUAL_ASSIGNMENT',
     'CANCELED','EXPIRED','COMPLETED')),
  CONSTRAINT ck_reservation_assigned CHECK
    (status <> 'CONFIRMED' OR
     (assigned_staff_id IS NOT NULL AND staff_availability_id IS NOT NULL
       AND confirmed_at IS NOT NULL))
) ENGINE=InnoDB;

-- 09. 감사/멱등 재요청 로그: entity_type, entity_id는 다형 식별자이므로 FK 없음
CREATE TABLE event_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  entity_type VARCHAR(60) NOT NULL,
  entity_id VARCHAR(90) NOT NULL,
  event_type VARCHAR(90) NOT NULL,
  from_value VARCHAR(120) NULL,
  to_value VARCHAR(120) NULL,
  actor_type VARCHAR(24) NOT NULL,
  actor_id BIGINT UNSIGNED NULL,
  request_id VARCHAR(90) NULL COMMENT '예약·배정·변경·취소 operation별 멱등 키',
  payload JSON NULL COMMENT '민감 채팅 원문/30일 요약/서류 원문 복사 금지',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_event_operation (request_id),
  KEY idx_event_entity (entity_type, entity_id, created_at),
  KEY idx_event_type_time (event_type, created_at),
  CONSTRAINT ck_event_actor CHECK (actor_type IN ('CUSTOMER','STAFF','AGENT','SYSTEM','ADMIN'))
) ENGINE=InnoDB;

-- ============================================================================
-- 별도 Spring 트랜잭션/비동기 작업이 강제할 불변식 (단순 FK/CHECK로 충분하지 않음):
-- 1. 예약 생성 조건: staff_review.result=PROCEED+decided_at NOT NULL,
--    service_case.case_status=REVIEW_PASSED, result_notified_at NOT NULL,
--    C02 또는 C01 방문 필요일 것. 둘 중 하나라도 실패하면 슬롯 미점유.
-- 2. 슬롯 SELECTED 정원 점유 0. PENDING_ASSIGNMENT/NEEDS_MANUAL_ASSIGNMENT/
--    CONFIRMED만 점유 1. 취소/만료 시 원자적으로 -1.
-- 3. 배정: staff_slot_availability.staff_id=assigned_staff_id,
--    staff_slot_availability.branch_slot_id=visit_reservation.branch_slot_id,
--    user.branch_id=branch_slot.branch_id, staff_task_capability.task_code 일치,
--    각 카운터 상한 및 시간 중복/겹치는 별도 slot 충돌 확인. 고정된 슬롯 시드 사용.
-- 4. 직원 검토 전 취소 vs 직원 검토 시작은 service_case 행 잠금으로 직렬화.
-- 5. 재검증은 (case_id, revision) 일치/최종 상태 미진입/파일 최신 확인.
-- 6. case_revision 스냅샷·heir/document_file 버전 갱신과 revision 증가 원자 처리.
-- 7. 7일 채팅/30일 case_statement/72시간 미제출 draft 각각 독립 배치.
-- 8. EMAIL: notification dedup_key는 알림 생성 중복 방지. SMTP 정확히 1회 전달 보장은 불가.
-- 9. check가 허용하는 사용자 role, 소속 직원/검토자 권한, 객체 소유 검증은 Spring Security.
-- 10. OCR은 document_type(source_kind=SHINHAN_OFFICIAL, source_verified=1,
--     ocr_enabled=1)인 경우만 호출. 일반 증명서는 SKIPPED(=NOT_APPLICABLE).
