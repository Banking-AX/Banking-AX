-- ERDCloud IMPORT ONLY / 기능명세서 v1.7, 32 tables, full columns
-- ERDCloud 화면 하단 [Import]에 붙여넣으세요. 이 파일은 시각화 전용이며 MySQL 실행용으로 사용하지 마세요.
-- MySQL generated columns are simplified to plain nullable columns for tool compatibility.
-- Actual DB constraints, generated column expressions, CHECK are in 02_MySQL8_실행용_원본DDL.sql.

-- 가상 영업점 기본정보
CREATE TABLE `branch` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  branch_code VARCHAR(32) NOT NULL COMMENT '영업점을 식별하는 업무 코드',
  name VARCHAR(100) NOT NULL COMMENT '이름',
  address VARCHAR(255) NOT NULL COMMENT '주소 (직원/영업점별 사용)',
  is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '사용 가능 여부',
  operating_policy JSON NULL COMMENT '영업점 운영시간·휴무·예약 제한 설정 JSON',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_branch_code (branch_code)
) COMMENT='가상 영업점 기본정보';

-- 고객·직원·관리자 계정
CREATE TABLE `user` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  login_id VARCHAR(80) NOT NULL COMMENT '로그인 아이디',
  password_hash VARCHAR(255) NOT NULL COMMENT '해시된 로그인 비밀번호',
  `role` VARCHAR(20) NOT NULL COMMENT 'CUSTOMER, STAFF, ADMIN',
  name VARCHAR(100) NOT NULL COMMENT '이름',
  birth_date DATE NULL COMMENT '생년월일',
  phone VARCHAR(30) NULL COMMENT '연락처',
  email VARCHAR(255) NULL COMMENT '이메일 주소',
  address VARCHAR(255) NULL COMMENT '주소 (직원/영업점별 사용)',
  branch_id BIGINT UNSIGNED NULL COMMENT '직원 소속 지점. 예약 전 사전검토 직원은 null 허용',
  can_pre_review BOOLEAN NOT NULL DEFAULT FALSE COMMENT '예약 이전 직원 사전검토 권한 보유 여부',
  is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '사용 가능 여부',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_user_login (login_id),
  KEY idx_user_branch_role (branch_id, `role`, is_active),
  CONSTRAINT fk_user_branch FOREIGN KEY (branch_id) REFERENCES branch(id)
) COMMENT='고객·직원·관리자 계정';

-- 직원별 처리 가능 업무코드
CREATE TABLE `staff_task_capability` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  staff_id BIGINT UNSIGNED NOT NULL COMMENT '직원 사용자 ID',
  task_code VARCHAR(40) NOT NULL COMMENT 'DEBT_ADJUSTMENT, INHERITANCE_DEPOSIT',
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '해당 직원의 업무 처리 권한 활성화 여부',
  UNIQUE KEY uk_staff_task (staff_id, task_code),
  KEY idx_capability_task (task_code, is_enabled),
  CONSTRAINT fk_cap_staff FOREIGN KEY (staff_id) REFERENCES `user`(id)
) COMMENT='직원별 처리 가능 업무코드';

-- 고객 채팅방
CREATE TABLE `chat_room` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  last_activity_at DATETIME(6) NULL COMMENT '해당 채팅방의 최근 활동 시각',
  oldest_retained_at DATETIME(6) NULL COMMENT '채팅방에서 보관 중인 가장 오래된 메시지 시각',
  KEY idx_chatroom_customer (customer_id, last_activity_at),
  CONSTRAINT fk_chatroom_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) COMMENT='고객 채팅방';

-- 최근 7일 대화 본문
CREATE TABLE `chat_message` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  chat_room_id BIGINT UNSIGNED NOT NULL COMMENT '연관 채팅방 ID',
  seq INT UNSIGNED NOT NULL COMMENT '채팅방 내 메시지 순서',
  sender_type VARCHAR(16) NOT NULL COMMENT 'CUSTOMER, AGENT, SYSTEM',
  content MEDIUMTEXT NOT NULL COMMENT '본문 내용',
  status VARCHAR(20) NOT NULL DEFAULT 'SENT' COMMENT '현재 진행 상태',
  citations JSON NULL COMMENT 'Agent 답변에 사용된 출처 메타데이터 JSON',
  sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '메시지 발송 시각 (7일 삭제 기준)',
  UNIQUE KEY uk_chatmessage_seq (chat_room_id, seq),
  KEY idx_chatmessage_expiry (sent_at),
  CONSTRAINT fk_chatmessage_room FOREIGN KEY (chat_room_id) REFERENCES chat_room(id)
) COMMENT='최근 7일 대화 본문';

-- 복합 업무 묶음 및 낙관적 락 버전
CREATE TABLE `workflow` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  chat_room_id BIGINT UNSIGNED NULL COMMENT '재신청 등 비채팅 진입 허용',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  status VARCHAR(24) NOT NULL COMMENT 'RUNNING/WAITING_CONTINUE/PAUSED/COMPLETED',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '마지막 변경 시각',
  KEY idx_workflow_customer_status (customer_id, status, updated_at),
  CONSTRAINT fk_workflow_chatroom FOREIGN KEY (chat_room_id) REFERENCES chat_room(id),
  CONSTRAINT fk_workflow_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) COMMENT='복합 업무 묶음 및 낙관적 락 버전';

-- 개별 업무 진행·보류·3일 임시입력 초기화
CREATE TABLE `work_item` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  workflow_id BIGINT UNSIGNED NOT NULL COMMENT '업무 묶음 ID',
  task_code VARCHAR(40) NOT NULL COMMENT 'A/B/C 세부 금융업무 식별 코드',
  sequence_no INT UNSIGNED NOT NULL COMMENT 'Workflow 내 업무 처리 순서',
  status VARCHAR(20) NOT NULL COMMENT 'PLANNED/ACTIVE/ON_HOLD/DONE/HANDED_OFF/FAILED/CANCELED',
  resume_step VARCHAR(80) NULL COMMENT 'ON_HOLD 상태에서 돌아올 입력 단계',
  draft_data JSON NULL COMMENT '아직 접수하지 않은 업무 임시 입력 JSON',
  failure_reason VARCHAR(255) NULL COMMENT '실패 또는 예외 발생 사유',
  cancel_reason VARCHAR(60) NULL COMMENT '업무 취소 사유',
  request_id VARCHAR(90) NOT NULL COMMENT 'API 중복 호출 방지용 멱등 요청 ID',
  last_activity_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '마지막 고객 활동 시각·미제출 3일 정책 기준',
  reset_at DATETIME(6) NULL COMMENT '3일 미진행 업무 임시입력 초기화 시각',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  active_workflow_id BIGINT UNSIGNED NULL COMMENT 'ACTIVE 업무 1개 제한을 위한 DB 생성 컬럼 [원본 DDL에서 GENERATED 계산 컬럼]',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_workitem_request (request_id),
  UNIQUE KEY uk_workitem_sequence (workflow_id, sequence_no),
  UNIQUE KEY uk_workitem_active (active_workflow_id),
  KEY idx_workitem_idle (status, last_activity_at),
  KEY idx_workitem_workflow (workflow_id, status),
  CONSTRAINT fk_workitem_workflow FOREIGN KEY (workflow_id) REFERENCES workflow(id)
) COMMENT='개별 업무 진행·보류·3일 임시입력 초기화';

-- A 조회·B 자동처리 결과와 변경 전후 스냅샷
CREATE TABLE `task_result` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  work_item_id BIGINT UNSIGNED NOT NULL COMMENT '해당 업무 항목 ID',
  result_type VARCHAR(40) NOT NULL COMMENT '조회·실행·서류검증 등 결과 유형',
  decision_code VARCHAR(60) NULL COMMENT 'Spring 업무 규칙의 처리 결과 코드',
  target_loan_id BIGINT UNSIGNED NULL COMMENT '필요 시 검색 가능한 대상 식별자; FK는 loan 뒤에 설정하지 않음',
  rule_version VARCHAR(40) NULL COMMENT '적용한 Java 검증 규칙 버전',
  basis_doc_id VARCHAR(60) NULL COMMENT 'RAG 근거 문서 ID',
  before_value JSON NULL COMMENT '업무 변경 전 상태 JSON',
  after_value JSON NULL COMMENT '업무 변경 후 상태 JSON',
  detail JSON NULL COMMENT 'B 자동처리 상세 결과 JSON',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  KEY idx_taskresult_workitem (work_item_id, created_at),
  CONSTRAINT fk_taskresult_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id)
) COMMENT='A 조회·B 자동처리 결과와 변경 전후 스냅샷';

-- 고객 업무 고지·동의 기록
CREATE TABLE `consent_record` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  work_item_id BIGINT UNSIGNED NOT NULL COMMENT '해당 업무 항목 ID',
  consent_type VARCHAR(60) NOT NULL COMMENT '고객 동의 유형 코드',
  notice_version VARCHAR(40) NOT NULL COMMENT '고객에게 노출한 안내 문구 버전',
  agreed BOOLEAN NOT NULL COMMENT '고객의 명시적 동의 여부',
  agreed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '고객 동의 확정 시각',
  KEY idx_consent_workitem (work_item_id, agreed_at),
  CONSTRAINT fk_consent_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id)
) COMMENT='고객 업무 고지·동의 기록';

-- 고객 Mock 수신 계좌
CREATE TABLE `deposit_account` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  account_no VARCHAR(40) NOT NULL COMMENT '가상 금융계좌 번호',
  account_name VARCHAR(120) NOT NULL COMMENT '가상 금융계좌 상품명',
  balance DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '현재 잔액',
  is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '사용 가능 여부',
  UNIQUE KEY uk_deposit_account_no (account_no),
  KEY idx_deposit_customer (customer_id),
  CONSTRAINT fk_deposit_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) COMMENT='고객 Mock 수신 계좌';

-- 계좌 Mock 거래내역
CREATE TABLE `account_transaction` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  account_id BIGINT UNSIGNED NOT NULL COMMENT '거래내역이 속한 입출금계좌 ID',
  tx_at DATETIME(6) NOT NULL COMMENT '거래 발생 시각',
  direction VARCHAR(10) NOT NULL COMMENT '입금 또는 출금 구분',
  amount DECIMAL(18,2) NOT NULL COMMENT '거래 금액',
  counterparty VARCHAR(120) NULL COMMENT '거래 상대방명',
  memo VARCHAR(255) NULL COMMENT '거래 적요',
  KEY idx_transaction_search (account_id, tx_at, id),
  CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES deposit_account(id)
) COMMENT='계좌 Mock 거래내역';

-- Mock 대출/연체/충당순서
CREATE TABLE `loan` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  loan_no VARCHAR(40) NOT NULL COMMENT '모의 대출 계좌번호',
  loan_name VARCHAR(120) NULL COMMENT '대출 상품명',
  principal_amount DECIMAL(18,2) NOT NULL COMMENT 'Mock 대출원금(요건 판단 참고)',
  balance DECIMAL(18,2) NOT NULL COMMENT '현재 잔액',
  interest_rate DECIMAL(7,4) NULL COMMENT '약정 금리',
  maturity_date DATE NULL COMMENT '대출 만기일',
  overdue_days INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '연체 경과 일수',
  acceleration_declared BOOLEAN NOT NULL DEFAULT FALSE COMMENT '기한의 이익 상실 여부',
  acceleration_date DATE NULL COMMENT '기한의 이익 상실 발생일',
  repayment_order VARCHAR(24) NOT NULL DEFAULT 'INTEREST_FIRST' COMMENT '대출 변제 충당순서 상태',
  outsourced BOOLEAN NOT NULL DEFAULT FALSE COMMENT '외부기관 위탁대출 여부',
  agreement_loan BOOLEAN NOT NULL DEFAULT FALSE COMMENT '협약대출 여부',
  debt_adjust_terminated_at DATE NULL COMMENT '과거 채무조정 합의 해지일',
  prev_adjustment_note VARCHAR(255) NULL COMMENT '이전 채무조정 검토 참고 메모',
  in_litigation BOOLEAN NOT NULL DEFAULT FALSE COMMENT '대출 관련 소송·조정 중 여부',
  other_procedure BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Mock 고객 진술; 직원 최종 확인',
  prescription_completed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '소멸시효 완성 여부 (Mock)',
  has_third_party_provider BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'PoC B-01 비대면 가정 허용',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_loan_no (loan_no),
  KEY idx_loan_customer (customer_id),
  CONSTRAINT fk_loan_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) COMMENT='Mock 대출/연체/충당순서';

-- Mock 청약통장 및 해지 결과
CREATE TABLE `housing_savings_account` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  account_no VARCHAR(40) NOT NULL COMMENT '가상 금융계좌 번호',
  joined_at DATE NOT NULL COMMENT '청약통장 가입일',
  paid_principal DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '청약통장 누적 납입 원금',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '현재 진행 상태',
  subscription_won BOOLEAN NOT NULL DEFAULT FALSE COMMENT '청약 당첨 여부',
  converted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '기존 계좌 전환신규 여부',
  applied_rate_type VARCHAR(32) NULL COMMENT '해지에 적용한 이율표 종류',
  tax_mock_data JSON NULL COMMENT 'B-02 Mock 과세내역; 실제 OCR 미적용',
  closed_at DATETIME(6) NULL COMMENT '모의 청약통장 해지 확정 시각',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_housing_account_no (account_no),
  KEY idx_housing_customer (customer_id),
  CONSTRAINT fk_housing_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)
) COMMENT='Mock 청약통장 및 해지 결과';

-- 피상속인·조회 전용 예금 요약
CREATE TABLE `decedent` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  name VARCHAR(100) NOT NULL COMMENT '이름',
  birth_date DATE NULL COMMENT '생년월일',
  death_date DATE NOT NULL COMMENT '피상속인 사망일',
  deposit_total DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '피상속인 예금원금 합계 Mock',
  deposits JSON NULL COMMENT '모의 피상속인 계좌 목록: 조회 전용'
) COMMENT='피상속인·조회 전용 예금 요약';

-- 가상 위임장 접수번호
CREATE TABLE `delegation_receipt` (
  receipt_no VARCHAR(80) PRIMARY KEY COMMENT '위임장 가상 접수번호',
  decedent_id BIGINT UNSIGNED NOT NULL COMMENT 'C-02 피상속인 ID',
  delegator_name VARCHAR(100) NOT NULL COMMENT '위임한 상속인 이름',
  agent_name VARCHAR(100) NOT NULL COMMENT '위임받은 대리인 이름',
  is_valid BOOLEAN NOT NULL DEFAULT FALSE COMMENT '가상 위임장 접수번호 확인 결과',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  KEY idx_delegation_decedent (decedent_id),
  CONSTRAINT fk_delegation_decedent FOREIGN KEY (decedent_id) REFERENCES decedent(id)
) COMMENT='가상 위임장 접수번호';

-- C업무 신청의 현행 상태·직원 검토·통지
CREATE TABLE `service_case` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  work_item_id BIGINT UNSIGNED NOT NULL COMMENT '해당 업무 항목 ID',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  case_type VARCHAR(40) NOT NULL COMMENT 'C-01 채무조정 또는 C-02 상속예금',
  case_status VARCHAR(36) NOT NULL DEFAULT 'DRAFT' COMMENT '직원 검토·접수 업무 상태',
  review_status VARCHAR(36) NULL COMMENT 'Agent 서류 사전점검 상태',
  revision INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Case 변경 버전 번호',
  previous_case_id BIGINT UNSIGNED NULL COMMENT '직원 최종 반려 후 재신청한 이전 케이스 ID',
  loan_id BIGINT UNSIGNED NULL COMMENT 'C-01 대상 Mock 대출 ID',
  decedent_id BIGINT UNSIGNED NULL COMMENT 'C-02 피상속인 ID',
  visit_required BOOLEAN NULL COMMENT 'C02=true; C01 직원 검토 후 결정. 예약과는 별개',
  application JSON NOT NULL COMMENT '현재 C신청 정보. 상속인 배열은 heir 테이블 사용',
  assigned_staff_id BIGINT UNSIGNED NULL COMMENT '방문 예약 확정 직원만(중복값); 예약 전 검토 직원 아님',
  submitted_at DATETIME(6) NULL COMMENT '고객이 검토 요청을 최종 제출한 시각',
  review_started_at DATETIME(6) NULL COMMENT '직원 검토 시작 시각',
  decided_at DATETIME(6) NULL COMMENT '직원 최종 검토 결과 등록 시각',
  result_notified_at DATETIME(6) NULL COMMENT '검토 결과 웹 알림 DB 생성 시각 (SMTP 도달 보장 아님)',
  canceled_at DATETIME(6) NULL COMMENT '취소 시각',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '마지막 변경 시각',
  UNIQUE KEY uk_case_workitem (work_item_id),
  KEY idx_case_customer_status (customer_id, case_status, created_at),
  KEY idx_case_reviewqueue (case_status, review_status, submitted_at),
  KEY idx_case_assigned (assigned_staff_id, case_status),
  CONSTRAINT fk_case_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_case_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT fk_case_prev FOREIGN KEY (previous_case_id) REFERENCES service_case(id),
  CONSTRAINT fk_case_loan FOREIGN KEY (loan_id) REFERENCES loan(id),
  CONSTRAINT fk_case_decedent FOREIGN KEY (decedent_id) REFERENCES decedent(id),
  CONSTRAINT fk_case_assigned FOREIGN KEY (assigned_staff_id) REFERENCES `user`(id)
) COMMENT='C업무 신청의 현행 상태·직원 검토·통지';

-- 상속인별 방문·위임·제출자 참조
CREATE TABLE `heir` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  person_ref VARCHAR(40) NOT NULL COMMENT 'Case 안에서 안정적인 사람 참조값',
  name VARCHAR(100) NOT NULL COMMENT '이름',
  birth_date DATE NULL COMMENT '생년월일',
  relationship VARCHAR(80) NOT NULL COMMENT '피상속인과 해당 상속인의 관계',
  is_applicant BOOLEAN NOT NULL DEFAULT FALSE COMMENT '이 상속인이 접수 신청인인지 여부',
  applicant_case_id BIGINT UNSIGNED NULL COMMENT 'Case마다 신청인 상속인 1명 보장을 위한 DB 생성 컬럼 [원본 DDL에서 GENERATED 계산 컬럼]',
  visit_status VARCHAR(28) NOT NULL DEFAULT 'UNKNOWN' COMMENT '상속인 방문 예정·불참·미확정 상태',
  delegation_method VARCHAR(28) NULL COMMENT 'NONE, DOCUMENT, RECEIPT',
  delegation_receipt_no VARCHAR(80) NULL COMMENT '슈퍼SOL 위임장 Mock 접수번호',
  customer_confirmed_at DATETIME(6) NULL COMMENT '고객이 방문·위임 내용을 직접 확인한 시각',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_heir_person (case_id, person_ref),
  UNIQUE KEY uk_heir_one_applicant (applicant_case_id),
  KEY idx_heir_visit (case_id, visit_status),
  CONSTRAINT fk_heir_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_heir_receipt FOREIGN KEY (delegation_receipt_no) REFERENCES delegation_receipt(receipt_no)
) COMMENT='상속인별 방문·위임·제출자 참조';

-- Case별 변경 버전과 제출 당시 스냅샷
CREATE TABLE `case_revision` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  revision INT UNSIGNED NOT NULL COMMENT 'Case 변경 버전 번호',
  application_snapshot JSON NOT NULL COMMENT '원본 업무 신청 스냅샷: 30일 진술 요약 복제 금지',
  heir_snapshot JSON NULL COMMENT '해당 버전의 상속인/방문·위임 입력값',
  document_manifest JSON NULL COMMENT '해당 버전에 유효한 document_file ID/버전 목록',
  change_reason VARCHAR(60) NOT NULL COMMENT 'Case 버전 변경 사유',
  created_by_user_id BIGINT UNSIGNED NULL COMMENT '변경 요청 사용자 ID',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_case_revision (case_id, revision),
  KEY idx_revision_created (case_id, created_at),
  CONSTRAINT fk_revision_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_revision_actor FOREIGN KEY (created_by_user_id) REFERENCES `user`(id)
) COMMENT='Case별 변경 버전과 제출 당시 스냅샷';

-- 채팅에서 추출한 업무 관련 최소 진술 요약(30일)
CREATE TABLE `case_statement` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  revision INT UNSIGNED NOT NULL COMMENT 'Case 변경 버전 번호',
  source_type VARCHAR(32) NOT NULL COMMENT '진술을 확보한 출처 유형',
  source_message_id BIGINT UNSIGNED NULL COMMENT '값만 보관. chat_message FK 금지',
  summary_text TEXT NOT NULL COMMENT '업무 관련 AI 최소 요약. 원문 발췌 영구보관 금지',
  confirmed_by_customer BOOLEAN NOT NULL DEFAULT FALSE COMMENT '업무 진술 요약 고객 확인 여부',
  source_at DATETIME(6) NULL COMMENT '해당 원문 진술이 발생한 시각',
  captured_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '업무 진술 요약 생성 시각',
  expires_at DATETIME(6) NOT NULL COMMENT '업무 진술 요약 생성 후 30일 만료 예정 시각',
  KEY idx_statement_ttl (expires_at),
  KEY idx_statement_case (case_id, revision),
  CONSTRAINT fk_statement_case FOREIGN KEY (case_id) REFERENCES service_case(id)
) COMMENT='채팅에서 추출한 업무 관련 최소 진술 요약(30일)';

-- 필요서류 종류 및 OCR 허용정책
CREATE TABLE `document_type` (
  code VARCHAR(60) PRIMARY KEY COMMENT '분류 식별 코드',
  name VARCHAR(140) NOT NULL COMMENT '이름',
  submission_kind VARCHAR(28) NOT NULL COMMENT '업로드·방문 지참·시스템 생성 등 제출 방법',
  valid_days INT UNSIGNED NULL COMMENT '해당 서류 발급일 기준 유효 기간(일)',
  per_person BOOLEAN NOT NULL DEFAULT FALSE COMMENT '상속인마다 개별 제출해야 하는 서류 여부',
  source_kind VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT 'SHINHAN_OFFICIAL, OTHER',
  source_doc_id VARCHAR(60) NULL COMMENT '해당 서류 유형의 신한 공식 근거 문서 ID',
  source_verified BOOLEAN NOT NULL DEFAULT FALSE COMMENT '원본 대조·검수 완료 여부',
  ocr_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '공식 양식 OCR 처리 허용 여부',
  is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '사용 가능 여부'
) COMMENT='필요서류 종류 및 OCR 허용정책';

-- WorkItem별 필수/조건부 서류 목록
CREATE TABLE `required_document` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  work_item_id BIGINT UNSIGNED NOT NULL COMMENT '해당 업무 항목 ID',
  document_type_code VARCHAR(60) NOT NULL COMMENT '서류 종류 코드',
  heir_id BIGINT UNSIGNED NULL COMMENT 'C02 제출자별 서류. person_ref는 heir에서 조회',
  scope_heir_key BIGINT UNSIGNED NULL COMMENT '상속인별 서류 중복 방지용 DB 생성 컬럼 [원본 DDL에서 GENERATED 계산 컬럼]',
  requirement VARCHAR(20) NOT NULL COMMENT 'REQUIRED, CONDITIONAL, VISIT_ONLY, OPTIONAL',
  condition_note VARCHAR(255) NULL COMMENT '조건부 서류가 필요한 상황 설명',
  rule_version VARCHAR(40) NULL COMMENT '적용한 Java 검증 규칙 버전',
  status VARCHAR(26) NOT NULL DEFAULT 'NOT_SUBMITTED' COMMENT '현재 진행 상태',
  human_checked_by BIGINT UNSIGNED NULL COMMENT '해당 서류를 확인한 직원 ID',
  human_checked_at DATETIME(6) NULL COMMENT '직원이 해당 서류를 확인한 시각',
  human_check_note VARCHAR(255) NULL COMMENT '직원이 남긴 해당 서류 확인 의견',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_required_workitem_type_person (work_item_id, document_type_code, scope_heir_key),
  KEY idx_required_workitem (work_item_id),
  KEY idx_required_heir (heir_id),
  CONSTRAINT fk_required_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_required_type FOREIGN KEY (document_type_code) REFERENCES document_type(code),
  CONSTRAINT fk_required_heir FOREIGN KEY (heir_id) REFERENCES heir(id),
  CONSTRAINT fk_required_checker FOREIGN KEY (human_checked_by) REFERENCES `user`(id)
) COMMENT='WorkItem별 필수/조건부 서류 목록';

-- 실제 업로드된 파일·버전별 메타데이터
CREATE TABLE `document_file` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  required_document_id BIGINT UNSIGNED NOT NULL COMMENT '업로드 파일이 속한 제출서류 슬롯 ID',
  version INT UNSIGNED NOT NULL COMMENT '낙관적 잠금 버전',
  is_latest BOOLEAN NOT NULL DEFAULT TRUE COMMENT '이 제출서류 슬롯의 최신 업로드인지',
  original_name VARCHAR(255) NOT NULL COMMENT '고객이 올린 원본 파일명',
  extension VARCHAR(12) NOT NULL COMMENT '업로드 파일 확장자',
  size_bytes BIGINT UNSIGNED NOT NULL COMMENT '업로드 파일 크기(바이트)',
  storage_path VARCHAR(512) NOT NULL COMMENT '실제 파일 저장 위치',
  issued_date DATE NULL COMMENT '고객이 입력한 서류 발급일',
  submitter VARCHAR(100) NULL COMMENT '고객이 입력한 서류 제출자명',
  uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '파일 업로드를 수행한 사용자 ID',
  uploaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '파일 업로드 시각',
  deleted_at DATETIME(6) NULL COMMENT '미제출 72시간 자동 초기화의 물리파일 삭제 추적',
  latest_required_document_id BIGINT UNSIGNED NULL COMMENT '서류 최신버전 1개만 허용하기 위한 DB 생성 컬럼 [원본 DDL에서 GENERATED 계산 컬럼]',
  UNIQUE KEY uk_file_slot_version (required_document_id, version),
  UNIQUE KEY uk_file_one_latest (latest_required_document_id),
  KEY idx_file_uploaded (uploaded_at),
  CONSTRAINT fk_file_required FOREIGN KEY (required_document_id) REFERENCES required_document(id),
  CONSTRAINT fk_file_uploader FOREIGN KEY (uploaded_by) REFERENCES `user`(id)
) COMMENT='실제 업로드된 파일·버전별 메타데이터';

-- OCR/텍스트 파싱/미적용 처리 이력
CREATE TABLE `document_extraction` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  document_file_id BIGINT UNSIGNED NOT NULL COMMENT '해당 분석·검증의 대상 문서 파일 ID',
  method VARCHAR(20) NOT NULL COMMENT 'NONE, TEXT_PARSER, CLOVA_OCR',
  status VARCHAR(28) NOT NULL COMMENT '서류 본문 처리 상태(미적용 포함)',
  detected_type VARCHAR(60) NULL COMMENT 'OCR/LLM이 식별한 실제 문서 종류 코드',
  raw_text_path VARCHAR(512) NULL COMMENT 'OCR 추출 원문 저장 위치',
  confidence DECIMAL(6,5) NULL COMMENT '문서 추출 신뢰도(참고값)',
  fields JSON NULL COMMENT 'OCR/LLM 구조화 추출 JSON',
  model_version VARCHAR(90) NULL COMMENT '사용한 LLM/OCR 모델 식별 버전',
  prompt_version VARCHAR(60) NULL COMMENT '검토 시 사용한 프롬프트 버전',
  failure_reason VARCHAR(255) NULL COMMENT '실패 또는 예외 발생 사유',
  started_at DATETIME(6) NULL COMMENT '처리 시작 시각',
  finished_at DATETIME(6) NULL COMMENT '처리 종료 시각',
  KEY idx_extraction_file (document_file_id, started_at),
  CONSTRAINT fk_extraction_file FOREIGN KEY (document_file_id) REFERENCES document_file(id)
) COMMENT='OCR/텍스트 파싱/미적용 처리 이력';

-- Agent 사전점검 실행·모델 버전·결과
CREATE TABLE `agent_review_run` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  revision INT UNSIGNED NOT NULL COMMENT 'Case 변경 버전 번호',
  check_type VARCHAR(30) NOT NULL DEFAULT 'STAFF_PRECHECK' COMMENT '직원 사전점검 종류',
  status VARCHAR(20) NOT NULL COMMENT '현재 진행 상태',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '검토 실행 재시도 횟수',
  model_version VARCHAR(90) NULL COMMENT '사용한 LLM/OCR 모델 식별 버전',
  prompt_version VARCHAR(60) NULL COMMENT '검토 시 사용한 프롬프트 버전',
  rule_version VARCHAR(40) NULL COMMENT '적용한 Java 검증 규칙 버전',
  failure_reason VARCHAR(255) NULL COMMENT '실패 또는 예외 발생 사유',
  report JSON NULL COMMENT '자동 사전점검 보고서 JSON',
  started_at DATETIME(6) NULL COMMENT '처리 시작 시각',
  finished_at DATETIME(6) NULL COMMENT '처리 종료 시각',
  UNIQUE KEY uk_reviewrun_revision (case_id, revision, check_type),
  CONSTRAINT fk_reviewrun_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision)
) COMMENT='Agent 사전점검 실행·모델 버전·결과';

-- 정형/비정형 불일치 및 근거
CREATE TABLE `review_item` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  revision INT UNSIGNED NOT NULL COMMENT 'Case 변경 버전 번호',
  agent_review_run_id BIGINT UNSIGNED NULL COMMENT '직원 Agent 실행 이력 ID',
  source VARCHAR(30) NOT NULL COMMENT 'SPRING_RULE, AGENT_SEMANTIC, PRE_SUBMISSION',
  category VARCHAR(40) NOT NULL COMMENT '오류·확인 항목 분류',
  result VARCHAR(36) NOT NULL COMMENT '업무 결과값',
  rule_id VARCHAR(70) NULL COMMENT 'Java 검증 규칙 코드',
  rule_version VARCHAR(40) NULL COMMENT '적용한 Java 검증 규칙 버전',
  basis_doc_id VARCHAR(60) NULL COMMENT 'RAG 근거 문서 ID',
  document_file_id BIGINT UNSIGNED NULL COMMENT '해당 분석·검증의 대상 문서 파일 ID',
  heir_id BIGINT UNSIGNED NULL COMMENT '제출 대상 상속인 ID',
  message TEXT NULL COMMENT '검토 결과나 불일치 사유 설명',
  evidence JSON NULL COMMENT '고객서류 원문 근거 / 업무규정 근거 분리',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  KEY idx_reviewitem_case_revision (case_id, revision),
  KEY idx_reviewitem_run (agent_review_run_id),
  CONSTRAINT fk_reviewitem_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_reviewitem_run FOREIGN KEY (agent_review_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_reviewitem_file FOREIGN KEY (document_file_id) REFERENCES document_file(id),
  CONSTRAINT fk_reviewitem_heir FOREIGN KEY (heir_id) REFERENCES heir(id)
) COMMENT='정형/비정형 불일치 및 근거';

-- AI 고객 추가 확인 질문과 답변
CREATE TABLE `clarification` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  review_item_id BIGINT UNSIGNED NULL COMMENT '추가 질문을 유발한 검토 항목 ID',
  requested_revision INT UNSIGNED NOT NULL COMMENT '추가 확인을 요청한 Case 버전',
  kind VARCHAR(28) NOT NULL COMMENT '추가 확인 요청의 종류',
  question TEXT NOT NULL COMMENT '고객에게 보낸 사실관계 확인 질문',
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '현재 진행 상태',
  created_by_run_id BIGINT UNSIGNED NULL COMMENT '질문을 생성한 Agent 실행 ID',
  due_at DATETIME(6) NULL COMMENT '질문 답변 만료 예정 시각',
  answer_text TEXT NULL COMMENT '고객이 제출한 추가 답변',
  confirmed_at DATETIME(6) NULL COMMENT '고객 확인·예약 확정 등 해당 행의 완료 시각',
  answer_file_id BIGINT UNSIGNED NULL COMMENT '추가 답변으로 받은 증빙 파일 ID',
  result_revision INT UNSIGNED NULL COMMENT '고객 답변 후 생성된 Case 버전',
  nonresponse_visit_assumption VARCHAR(28) NULL COMMENT 'UNCONFIRMED_ABSENT only, legal determination forbidden',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  KEY idx_clarification_case_status (case_id, status),
  CONSTRAINT fk_clarification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_clarification_item FOREIGN KEY (review_item_id) REFERENCES review_item(id),
  CONSTRAINT fk_clarification_run FOREIGN KEY (created_by_run_id) REFERENCES agent_review_run(id),
  CONSTRAINT fk_clarification_file FOREIGN KEY (answer_file_id) REFERENCES document_file(id)
) COMMENT='AI 고객 추가 확인 질문과 답변';

-- 예약 전 직원의 검토·최종 사전검토 판정
CREATE TABLE `staff_review` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  staff_id BIGINT UNSIGNED NOT NULL COMMENT '예약 전 사전검토 직원; 방문 담당 직원과 별개',
  revision INT UNSIGNED NOT NULL COMMENT 'Case 변경 버전 번호',
  started_at DATETIME(6) NOT NULL COMMENT '처리 시작 시각',
  decided_at DATETIME(6) NULL COMMENT '직원이 검토 결과를 확정한 시각',
  result VARCHAR(32) NULL COMMENT 'PROCEED / REQUIREMENT_FAILED / DOCUMENT_INSUFFICIENT',
  opinion_draft TEXT NULL COMMENT '직원 검토 의견 초안',
  opinion_final TEXT NULL COMMENT '직원이 최종 확정한 검토 의견',
  follow_up_final TEXT NULL COMMENT '직원이 확정한 후속 조치 안내',
  adjustment JSON NULL COMMENT 'C-01 모의 조정 내용',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  final_case_id BIGINT UNSIGNED NULL COMMENT 'Case당 확정 직원 검토 1건 보장을 위한 DB 생성 컬럼 [원본 DDL에서 GENERATED 계산 컬럼]',
  UNIQUE KEY uk_staffreview_one_final (final_case_id),
  KEY idx_staffreview_staff (staff_id, started_at),
  KEY idx_staffreview_case (case_id, started_at),
  CONSTRAINT fk_staffreview_revision FOREIGN KEY (case_id, revision)
    REFERENCES case_revision(case_id, revision),
  CONSTRAINT fk_staffreview_staff FOREIGN KEY (staff_id) REFERENCES `user`(id)
) COMMENT='예약 전 직원의 검토·최종 사전검토 판정';

-- 웹·이메일 알림 및 발송 상태
CREATE TABLE `notification` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  customer_id BIGINT UNSIGNED NOT NULL COMMENT '신청 고객 사용자 ID',
  case_id BIGINT UNSIGNED NULL COMMENT 'C 업무 알림 대상 Case ID',
  work_item_id BIGINT UNSIGNED NULL COMMENT 'B 업무 알림 대상 WorkItem ID',
  task_result_id BIGINT UNSIGNED NULL COMMENT 'B 업무 처리 결과 연결 ID',
  type VARCHAR(60) NOT NULL COMMENT '유형',
  title VARCHAR(160) NOT NULL COMMENT '사용자에게 표시할 알림 제목',
  content TEXT NOT NULL COMMENT '본문 내용',
  dedup_key VARCHAR(160) NOT NULL COMMENT '알림 중복 생성을 막는 식별값',
  read_at DATETIME(6) NULL COMMENT '고객이 웹 알림을 읽은 시각',
  email_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT 'SMTP 알림 발송 상태',
  email_attempts INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'SMTP 발송 시도 횟수',
  email_last_error VARCHAR(255) NULL COMMENT '최근 이메일 발송 실패 사유',
  email_sent_at DATETIME(6) NULL COMMENT '이메일 최종 발송 성공 시각',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_notification_dedup (dedup_key),
  KEY idx_notification_customer (customer_id, created_at),
  KEY idx_notification_email (email_status, created_at),
  CONSTRAINT fk_notification_customer FOREIGN KEY (customer_id) REFERENCES `user`(id),
  CONSTRAINT fk_notification_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_notification_workitem FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_notification_result FOREIGN KEY (task_result_id) REFERENCES task_result(id)
) COMMENT='웹·이메일 알림 및 발송 상태';

-- 영업점 예약 가능 시간과 정원
CREATE TABLE `branch_slot` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  branch_id BIGINT UNSIGNED NOT NULL COMMENT '영업점 ID',
  start_at DATETIME(6) NOT NULL COMMENT '예약 시작 시각(UTC 저장)',
  end_at DATETIME(6) NOT NULL COMMENT '예약 종료 시각(UTC 저장)',
  capacity INT UNSIGNED NOT NULL COMMENT '영업점 시간 슬롯 총 예약 정원',
  reserved_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '예약 정원 중 현재 점유 건수',
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '현재 진행 상태',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_branchslot_time (branch_id, start_at, end_at),
  KEY idx_branchslot_available (branch_id, start_at, status),
  CONSTRAINT fk_branchslot_branch FOREIGN KEY (branch_id) REFERENCES branch(id)
) COMMENT='영업점 예약 가능 시간과 정원';

-- 직원별 슬롯 수용 가능량·점유 카운터
CREATE TABLE `staff_slot_availability` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  staff_id BIGINT UNSIGNED NOT NULL COMMENT '직원 사용자 ID',
  branch_slot_id BIGINT UNSIGNED NOT NULL COMMENT '예약하려는 영업점 시간 슬롯 ID',
  available BOOLEAN NOT NULL DEFAULT TRUE COMMENT '해당 직원이 해당 시각에 예약 가능한지 여부',
  max_assignments INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '이 직원 슬롯의 최대 배정 건수',
  assigned_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '직원 슬롯의 현재 배정 건수',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_staff_slot (staff_id, branch_slot_id),
  KEY idx_slot_staff_availability (branch_slot_id, available),
  CONSTRAINT fk_staffslot_staff FOREIGN KEY (staff_id) REFERENCES `user`(id),
  CONSTRAINT fk_staffslot_slot FOREIGN KEY (branch_slot_id) REFERENCES branch_slot(id)
) COMMENT='직원별 슬롯 수용 가능량·점유 카운터';

-- 직원 검토 완료 후 예약·AI 추천·Spring 배정 확정
CREATE TABLE `visit_reservation` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  case_id BIGINT UNSIGNED NOT NULL COMMENT 'C업무 신청 케이스 식별자',
  branch_slot_id BIGINT UNSIGNED NOT NULL COMMENT '예약하려는 영업점 시간 슬롯 ID',
  status VARCHAR(32) NOT NULL DEFAULT 'SELECTED' COMMENT 'SELECTED/PENDING_ASSIGNMENT/CONFIRMED/NEEDS_MANUAL_ASSIGNMENT/CANCELED/EXPIRED/COMPLETED',
  assigned_staff_id BIGINT UNSIGNED NULL COMMENT 'Spring 검증 후 확정된 방문 담당 직원 ID',
  staff_availability_id BIGINT UNSIGNED NULL COMMENT '배정된 직원의 예약 가용 슬롯 ID',
  recommended_staff_id BIGINT UNSIGNED NULL COMMENT 'Agent가 추천한 직원 ID',
  assignment_reason JSON NULL COMMENT '업무코드/업무량 근거만, 경험점수 제외',
  assignment_model_version VARCHAR(90) NULL COMMENT '담당 직원 추천에 사용한 모델 버전',
  assignment_attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'AI 직원 배정 시도 횟수',
  request_id VARCHAR(90) NOT NULL COMMENT 'API 중복 호출 방지용 멱등 요청 ID',
  selected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '고객이 방문 슬롯을 선택한 시각',
  confirmed_at DATETIME(6) NULL COMMENT '고객 확인·예약 확정 등 해당 행의 완료 시각',
  canceled_at DATETIME(6) NULL COMMENT '취소 시각',
  expires_at DATETIME(6) NULL COMMENT '만료 정책에 따른 최종 기한',
  version INT NOT NULL DEFAULT 0 COMMENT '낙관적 잠금 버전',
  UNIQUE KEY uk_reservation_case (case_id),
  UNIQUE KEY uk_reservation_create_request (request_id),
  KEY idx_reservation_slot_status (branch_slot_id, status),
  KEY idx_reservation_staff (assigned_staff_id, status),
  CONSTRAINT fk_reservation_case FOREIGN KEY (case_id) REFERENCES service_case(id),
  CONSTRAINT fk_reservation_slot FOREIGN KEY (branch_slot_id) REFERENCES branch_slot(id),
  CONSTRAINT fk_reservation_staff FOREIGN KEY (assigned_staff_id) REFERENCES `user`(id),
  CONSTRAINT fk_reservation_staffslot FOREIGN KEY (staff_availability_id) REFERENCES staff_slot_availability(id),
  CONSTRAINT fk_reservation_recommended FOREIGN KEY (recommended_staff_id) REFERENCES `user`(id)
) COMMENT='직원 검토 완료 후 예약·AI 추천·Spring 배정 확정';

-- 상태·도구·예약변경 등 공통 이벤트 및 멱등키
CREATE TABLE `event_log` (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '행 기본키',
  entity_type VARCHAR(60) NOT NULL COMMENT '이벤트가 발생한 도메인 개체 유형',
  entity_id VARCHAR(90) NOT NULL COMMENT '이벤트 대상 개체의 식별자',
  event_type VARCHAR(90) NOT NULL COMMENT '발생한 업무 이벤트 코드',
  from_value VARCHAR(120) NULL COMMENT '변경 이전 상태 또는 주요 값',
  to_value VARCHAR(120) NULL COMMENT '변경 이후 상태 또는 주요 값',
  actor_type VARCHAR(24) NOT NULL COMMENT '이벤트를 발생시킨 고객/직원/Agent/시스템 유형',
  actor_id BIGINT UNSIGNED NULL COMMENT '이벤트를 수행한 사용자 ID(해당 시)',
  request_id VARCHAR(90) NULL COMMENT '예약·배정·변경·취소 operation별 멱등 키',
  payload JSON NULL COMMENT '민감 채팅 원문/30일 요약/서류 원문 복사 금지',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '생성 시각',
  UNIQUE KEY uk_event_operation (request_id),
  KEY idx_event_entity (entity_type, entity_id, created_at),
  KEY idx_event_type_time (event_type, created_at)
) COMMENT='상태·도구·예약변경 등 공통 이벤트 및 멱등키';
