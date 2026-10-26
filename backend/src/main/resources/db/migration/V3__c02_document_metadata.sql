-- C-02 document metadata. Parent Case/Workflow tables are provided by V1/V2.
-- This is additive and mirrors docs/01_schema_mysql8.sql, with only the
-- metadata columns required by JpaDocumentPort added to document_file.

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

CREATE TABLE heir (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  case_id BIGINT UNSIGNED NOT NULL,
  person_ref VARCHAR(40) NOT NULL,
  name VARCHAR(100) NOT NULL,
  birth_date DATE NULL,
  relationship VARCHAR(80) NOT NULL,
  is_applicant BOOLEAN NOT NULL DEFAULT FALSE,
  applicant_case_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN is_applicant=TRUE THEN case_id ELSE NULL END) STORED,
  visit_status VARCHAR(28) NOT NULL DEFAULT 'UNKNOWN',
  delegation_method VARCHAR(28) NULL,
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

CREATE TABLE document_type (
  code VARCHAR(60) PRIMARY KEY,
  name VARCHAR(140) NOT NULL,
  submission_kind VARCHAR(28) NOT NULL,
  valid_days INT UNSIGNED NULL,
  per_person BOOLEAN NOT NULL DEFAULT FALSE,
  source_kind VARCHAR(32) NOT NULL DEFAULT 'OTHER',
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
  heir_id BIGINT UNSIGNED NULL,
  scope_heir_key BIGINT UNSIGNED GENERATED ALWAYS AS (COALESCE(heir_id, 0)) STORED,
  requirement VARCHAR(20) NOT NULL,
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
  content_type VARCHAR(100) NULL,
  checksum CHAR(64) NULL,
  case_revision INT UNSIGNED NOT NULL DEFAULT 0,
  issued_date DATE NULL,
  submitter VARCHAR(100) NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  uploaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_at DATETIME(6) NULL,
  latest_required_document_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN is_latest=TRUE THEN required_document_id ELSE NULL END) STORED,
  UNIQUE KEY uk_file_slot_version (required_document_id, version),
  UNIQUE KEY uk_file_one_latest (latest_required_document_id),
  KEY idx_file_uploaded (uploaded_at),
  CONSTRAINT fk_file_required FOREIGN KEY (required_document_id) REFERENCES required_document(id),
  CONSTRAINT fk_file_uploader FOREIGN KEY (uploaded_by) REFERENCES `user`(id),
  CONSTRAINT ck_file_size CHECK (size_bytes <= 10485760)
) ENGINE=InnoDB;
