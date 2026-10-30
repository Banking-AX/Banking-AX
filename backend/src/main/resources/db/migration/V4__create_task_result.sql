CREATE TABLE task_result (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT UNSIGNED NOT NULL,
  result_type VARCHAR(40) NOT NULL,
  decision_code VARCHAR(60) NULL,
  target_loan_id BIGINT UNSIGNED NULL,
  rule_version VARCHAR(40) NULL,
  basis_doc_id VARCHAR(60) NULL,
  before_value JSON NULL,
  after_value JSON NULL,
  detail JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_taskresult_workitem (work_item_id),
  KEY idx_taskresult_target_loan (target_loan_id),
  CONSTRAINT fk_taskresult_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id) ON DELETE RESTRICT,
  CONSTRAINT fk_taskresult_target_loan FOREIGN KEY (target_loan_id) REFERENCES loan(id) ON DELETE RESTRICT
) ENGINE=InnoDB;
