CREATE TABLE chat_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  chat_room_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(64) NULL,
  seq INT UNSIGNED NOT NULL,
  sender_type VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SENT',
  citations JSON NULL,
  sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chatmessage_seq (chat_room_id, seq),
  UNIQUE KEY uk_chatmessage_request (chat_room_id, request_id),
  KEY idx_chatmessage_expiry (sent_at),
  CONSTRAINT fk_chatmessage_room FOREIGN KEY (chat_room_id)
    REFERENCES chat_room(id) ON DELETE RESTRICT,
  CONSTRAINT ck_chatmessage_sender CHECK (sender_type IN ('CUSTOMER','AGENT','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
