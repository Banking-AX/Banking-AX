CREATE TABLE refresh_token (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  token_hash CHAR(64) NOT NULL,
  family_id CHAR(36) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  absolute_expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  replaced_by_id BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_token_family (family_id),
  KEY idx_refresh_token_user_expiry (user_id, absolute_expires_at),
  CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE RESTRICT,
  CONSTRAINT fk_refresh_token_replacement FOREIGN KEY (replaced_by_id) REFERENCES refresh_token(id) ON DELETE RESTRICT,
  CONSTRAINT chk_refresh_token_expiry CHECK (absolute_expires_at > issued_at),
  CONSTRAINT chk_refresh_token_replacement CHECK (replaced_by_id IS NULL OR revoked_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
