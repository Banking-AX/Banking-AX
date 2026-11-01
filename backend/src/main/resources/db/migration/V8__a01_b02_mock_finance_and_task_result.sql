-- Deployment subset for A-01/B-02. Additive only; all timestamps are UTC.
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

CREATE TABLE deposit_account (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL,
  account_no VARCHAR(40) NOT NULL, account_name VARCHAR(120) NOT NULL,
  balance DECIMAL(18,2) NOT NULL DEFAULT 0, is_active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uk_deposit_account_no (account_no), KEY idx_deposit_customer (customer_id),
  CONSTRAINT fk_deposit_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) ENGINE=InnoDB;
CREATE TABLE account_transaction (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, account_id BIGINT UNSIGNED NOT NULL,
  tx_at DATETIME(6) NOT NULL, direction VARCHAR(10) NOT NULL, amount DECIMAL(18,2) NOT NULL,
  counterparty VARCHAR(120) NULL, memo VARCHAR(255) NULL,
  KEY idx_transaction_search (account_id, tx_at, id),
  CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES deposit_account(id),
  CONSTRAINT ck_transaction_direction CHECK (direction IN ('IN','OUT')),
  CONSTRAINT ck_transaction_amount CHECK (amount >= 0)
) ENGINE=InnoDB;
CREATE TABLE housing_savings_account (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL,
  account_no VARCHAR(40) NOT NULL, joined_at DATE NOT NULL,
  paid_principal DECIMAL(18,2) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  subscription_won BOOLEAN NOT NULL DEFAULT FALSE, converted BOOLEAN NOT NULL DEFAULT FALSE,
  applied_rate_type VARCHAR(32) NULL, tax_mock_data JSON NULL, closed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0, UNIQUE KEY uk_housing_account_no (account_no),
  KEY idx_housing_customer (customer_id),
  CONSTRAINT fk_housing_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT ck_housing_status CHECK (status IN ('ACTIVE','CLOSED'))
) ENGINE=InnoDB;

-- task_result already receives decision_code/rule_version/before_value/after_value in V4.
