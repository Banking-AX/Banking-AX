CREATE TABLE document_extraction (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  document_file_id BIGINT UNSIGNED NOT NULL,
  case_id BIGINT UNSIGNED NOT NULL,
  revision INT UNSIGNED NOT NULL,
  method VARCHAR(20) NOT NULL, status VARCHAR(24) NOT NULL,
  extracted JSON NULL, failure_reason VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_extraction_file_revision(document_file_id,revision),
  CONSTRAINT fk_extraction_file FOREIGN KEY(document_file_id) REFERENCES document_file(id),
  CONSTRAINT fk_extraction_revision FOREIGN KEY(case_id,revision) REFERENCES case_revision(case_id,revision),
  CONSTRAINT ck_extraction_method CHECK(method IN ('NONE','MOCK','CLOVA_GENERAL','CLOVA_TEMPLATE')),
  CONSTRAINT ck_extraction_status CHECK(status IN ('NOT_APPLICABLE','SUCCEEDED','FAILED_NEEDS_STAFF'))
) ENGINE=InnoDB;

CREATE TABLE visit_reservation (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, case_id BIGINT UNSIGNED NOT NULL,
 customer_id BIGINT UNSIGNED NOT NULL, branch_id BIGINT UNSIGNED NOT NULL,
 assigned_staff_id BIGINT UNSIGNED NULL, starts_at DATETIME(6) NOT NULL,
 status VARCHAR(32) NOT NULL, version INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_reservation_case(case_id), UNIQUE KEY uk_reservation_staff_time(assigned_staff_id,starts_at),
 CONSTRAINT fk_reservation_case FOREIGN KEY(case_id) REFERENCES service_case(id),
 CONSTRAINT fk_reservation_customer FOREIGN KEY(customer_id) REFERENCES `user`(id),
 CONSTRAINT fk_reservation_branch FOREIGN KEY(branch_id) REFERENCES branch(id),
 CONSTRAINT fk_reservation_staff FOREIGN KEY(assigned_staff_id) REFERENCES `user`(id),
 CONSTRAINT ck_reservation_status CHECK(status IN ('SELECTED','PENDING_ASSIGNMENT','CONFIRMED','NEEDS_MANUAL_ASSIGNMENT','CANCELED'))
) ENGINE=InnoDB;
