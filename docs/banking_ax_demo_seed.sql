-- Banking AX Workflow Agent : 대표 고객 1명 + 직원 1명 전체 ERD 시드
-- 대상: 기존 MySQL 8 실행용 DDL로 생성된 빈 테이블. 시각화 전용 DDL은 실행 불가/제약 누락 가능.
-- 모든 인물, 계좌, 영업점, 상품 명칭, 심사 결과, 서류, 근거문서 ID는 가상의 테스트 데이터입니다.
-- 주의: 현재 실제 사업 규칙과 상충하는 항목은 규칙 확정 전 예시일 뿐입니다.
-- 비밀번호 해시는 환경별 BCrypt hash로 바꿔야 합니다. 아래는 개발용 비활성 placeholder.
-- 기존 DB에 실행 시 PK 충돌 가능. 별도 빈 개발 DB에서 1회 사용.
--/generated/ 컬럼: work_item.active_workflow_id, heir.applicant_case_id,
-- required_document.scope_heir_key, document_file.latest_required_document_id,
-- staff_review.final_case_id는 원본 DDL GENERATED라고 주석에 명시되므로 INSERT에서 제외.
START TRANSACTION;


-- branch

INSERT INTO `branch` (`id`, `branch_code`, `name`, `address`, `is_active`, `operating_policy`, `created_at`) VALUES (1, 'MOCK-001', '테스트 가상 영업점', '가상 주소 (실제 영업점 아님)', 1, '{"timezone":"Asia/Seoul","open":"09:00","close":"16:00","weekdays":[1,2,3,4,5]}', '2026-10-21 09:00:00.000000');



-- user

INSERT INTO `user` (`id`, `login_id`, `password_hash`, `role`, `name`, `birth_date`, `phone`, `email`, `address`, `branch_id`, `can_pre_review`, `is_active`, `created_at`, `version`) VALUES (1, 'customer_demo', 'REPLACE_WITH_BCRYPT_HASH', 'CUSTOMER', '김민수', '1997-04-12', '010-0000-0001', 'customer-demo@example.test', '가상 고객 주소', NULL, 0, 1, '2026-10-21 09:00:00.000000', 0);

INSERT INTO `user` (`id`, `login_id`, `password_hash`, `role`, `name`, `birth_date`, `phone`, `email`, `address`, `branch_id`, `can_pre_review`, `is_active`, `created_at`, `version`) VALUES (101, 'staff_demo', 'REPLACE_WITH_BCRYPT_HASH', 'STAFF', '박지현', '1992-06-18', '010-0000-0101', 'staff-demo@example.test', '가상 영업점 주소', 1, 1, 1, '2026-10-21 09:00:00.000000', 0);



-- staff_task_capability

INSERT INTO `staff_task_capability` (`id`, `staff_id`, `task_code`, `is_enabled`) VALUES (1, 101, 'C-01', 1);

INSERT INTO `staff_task_capability` (`id`, `staff_id`, `task_code`, `is_enabled`) VALUES (2, 101, 'C-02', 1);



-- deposit_account

INSERT INTO `deposit_account` (`id`, `customer_id`, `account_no`, `account_name`, `balance`, `is_active`) VALUES (1, 1, 'MOCK-110-0001', '테스트 입출금통장', 3500000, 1);

INSERT INTO `deposit_account` (`id`, `customer_id`, `account_no`, `account_name`, `balance`, `is_active`) VALUES (2, 1, 'MOCK-110-0002', '테스트 생활비통장', 850000, 1);



-- account_transaction

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (1, 1, '2026-10-01 09:00:00', 'IN', 3000000, '가상회사', '급여');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (2, 1, '2026-10-03 12:00:00', 'OUT', 54000, '테스트마트', '생활비');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (3, 1, '2026-10-08 13:30:00', 'OUT', 35000, '테스트카페', '식비');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (4, 1, '2026-10-14 09:30:00', 'IN', 100000, '테스트환급', '환급');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (5, 1, '2026-10-19 18:20:00', 'OUT', 450000, '가상임대인', '월세');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (6, 1, '2026-09-10 10:00:00', 'IN', 2800000, '가상회사', '9월 급여');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (7, 2, '2026-10-02 09:10:00', 'IN', 500000, '김민수', '계좌이체');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (8, 2, '2026-10-07 14:30:00', 'OUT', 62000, '가상통신사', '통신비');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (9, 2, '2026-10-18 12:40:00', 'OUT', 25000, '테스트서점', '도서');

INSERT INTO `account_transaction` (`id`, `account_id`, `tx_at`, `direction`, `amount`, `counterparty`, `memo`) VALUES (10, 2, '2026-08-15 09:00:00', 'OUT', 15000, '테스트구독', '구독');



-- loan

INSERT INTO `loan` (`id`, `customer_id`, `loan_no`, `loan_name`, `principal_amount`, `balance`, `interest_rate`, `maturity_date`, `overdue_days`, `acceleration_declared`, `acceleration_date`, `repayment_order`, `outsourced`, `agreement_loan`, `debt_adjust_terminated_at`, `prev_adjustment_note`, `in_litigation`, `other_procedure`, `prescription_completed`, `has_third_party_provider`, `version`) VALUES (1, 1, 'MOCK-LN-001', '가상 일반신용대출', 12000000, 9000000, 5.2, '2028-08-31', 0, 0, NULL, 'DEFAULT', 0, 0, NULL, NULL, 0, 0, 0, 0, 0);

INSERT INTO `loan` (`id`, `customer_id`, `loan_no`, `loan_name`, `principal_amount`, `balance`, `interest_rate`, `maturity_date`, `overdue_days`, `acceleration_declared`, `acceleration_date`, `repayment_order`, `outsourced`, `agreement_loan`, `debt_adjust_terminated_at`, `prev_adjustment_note`, `in_litigation`, `other_procedure`, `prescription_completed`, `has_third_party_provider`, `version`) VALUES (2, 1, 'MOCK-LN-002', '가상 생활안정 대출', 24000000, 20000000, 6.1, '2029-05-31', 45, 0, NULL, 'DEFAULT', 0, 0, NULL, '소득감소 사유의 채무조정 사전접수용', 0, 0, 0, 0, 0);

INSERT INTO `loan` (`id`, `customer_id`, `loan_no`, `loan_name`, `principal_amount`, `balance`, `interest_rate`, `maturity_date`, `overdue_days`, `acceleration_declared`, `acceleration_date`, `repayment_order`, `outsourced`, `agreement_loan`, `debt_adjust_terminated_at`, `prev_adjustment_note`, `in_litigation`, `other_procedure`, `prescription_completed`, `has_third_party_provider`, `version`) VALUES (3, 1, 'MOCK-LN-003', '가상 제외조건 검증 대출', 5000000, 4800000, 7.0, '2027-12-31', 0, 0, NULL, 'DEFAULT', 1, 0, NULL, '외부기관 위탁 상태를 가정한 실패 테스트', 0, 0, 0, 1, 0);



-- housing_savings_account

INSERT INTO `housing_savings_account` (`id`, `customer_id`, `account_no`, `joined_at`, `paid_principal`, `status`, `subscription_won`, `converted`, `applied_rate_type`, `tax_mock_data`, `closed_at`, `version`) VALUES (1, 1, 'MOCK-HS-001', '2023-10-01', 6000000, 'ACTIVE', 0, 0, NULL, '{"has_tax_history":false,"preferential_rate_requested":false,"tax_exempt_requested":false}', NULL, 0);

INSERT INTO `housing_savings_account` (`id`, `customer_id`, `account_no`, `joined_at`, `paid_principal`, `status`, `subscription_won`, `converted`, `applied_rate_type`, `tax_mock_data`, `closed_at`, `version`) VALUES (2, 1, 'MOCK-HS-002', '2022-01-01', 2500000, 'CLOSED', 0, 1, 'MOCK_GENERAL', '{"mock":true}', '2026-10-18 10:00:00', 0);



-- decedent

INSERT INTO `decedent` (`id`, `name`, `birth_date`, `death_date`, `deposit_total`, `deposits`) VALUES (1, '김영식', '1948-03-12', '2026-09-15', 12500000, '[{"account_no":"MOCK-DEC-001","principal":12500000}]');

INSERT INTO `decedent` (`id`, `name`, `birth_date`, `death_date`, `deposit_total`, `deposits`) VALUES (2, '김정숙', '1952-05-04', '2026-10-01', 8100000, '[{"account_no":"MOCK-DEC-002","principal":8100000}]');



-- delegation_receipt

INSERT INTO `delegation_receipt` (`receipt_no`, `decedent_id`, `delegator_name`, `agent_name`, `is_valid`, `created_at`) VALUES ('MOCK-DEL-001', 1, '김하나', '김민수', 1, '2026-10-21 09:00:00.000000');

INSERT INTO `delegation_receipt` (`receipt_no`, `decedent_id`, `delegator_name`, `agent_name`, `is_valid`, `created_at`) VALUES ('MOCK-DEL-BAD', 2, '김둘', '김민수', 0, '2026-10-21 09:00:00.000000');



-- chat_room

INSERT INTO `chat_room` (`id`, `customer_id`, `created_at`, `last_activity_at`, `oldest_retained_at`) VALUES (1, 1, '2026-10-21 09:00:00.000000', '2026-10-23 12:00:00.000000', '2026-10-21 09:00:00.000000');

INSERT INTO `chat_room` (`id`, `customer_id`, `created_at`, `last_activity_at`, `oldest_retained_at`) VALUES (2, 1, '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000');



-- chat_message

INSERT INTO `chat_message` (`id`, `chat_room_id`, `seq`, `sender_type`, `content`, `status`, `citations`, `sent_at`) VALUES (1, 1, 1, 'CUSTOMER', '최근 한 달 거래내역과 대출 정보를 알려줘', 'SENT', NULL, '2026-10-21 09:00:00.000000');

INSERT INTO `chat_message` (`id`, `chat_room_id`, `seq`, `sender_type`, `content`, `status`, `citations`, `sent_at`) VALUES (2, 1, 2, 'AGENT', '입출금계좌 2개와 대출계좌 3개가 확인되었습니다. 어떤 계좌를 조회할까요?', 'SENT', NULL, '2026-10-21 09:00:00.000000');

INSERT INTO `chat_message` (`id`, `chat_room_id`, `seq`, `sender_type`, `content`, `status`, `citations`, `sent_at`) VALUES (3, 1, 3, 'CUSTOMER', '첫 번째 통장 거래내역부터 보여줘', 'SENT', NULL, '2026-10-23 12:00:00.000000');

INSERT INTO `chat_message` (`id`, `chat_room_id`, `seq`, `sender_type`, `content`, `status`, `citations`, `sent_at`) VALUES (4, 2, 1, 'CUSTOMER', '채무조정 사전접수 진행 상황 알려줘', 'SENT', NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `chat_message` (`id`, `chat_room_id`, `seq`, `sender_type`, `content`, `status`, `citations`, `sent_at`) VALUES (5, 2, 2, 'AGENT', '접수된 Case의 진행 상태를 확인할 수 있습니다.', 'SENT', NULL, '2026-10-22 10:00:00.000000');



-- workflow

INSERT INTO `workflow` (`id`, `chat_room_id`, `customer_id`, `status`, `version`, `created_at`, `updated_at`) VALUES (1, 1, 1, 'WAITING_CONTINUE', 0, '2026-10-21 09:00:00.000000', '2026-10-23 12:00:00.000000');

INSERT INTO `workflow` (`id`, `chat_room_id`, `customer_id`, `status`, `version`, `created_at`, `updated_at`) VALUES (2, 2, 1, 'COMPLETED', 0, '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000');

INSERT INTO `workflow` (`id`, `chat_room_id`, `customer_id`, `status`, `version`, `created_at`, `updated_at`) VALUES (3, NULL, 1, 'COMPLETED', 0, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');



-- work_item

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (1, 1, 'A-01', 1, 'DONE', NULL, '{"account_id":1,"period":"LAST_1_MONTH"}', NULL, NULL, 'seed-a01', '2026-10-23 12:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (2, 1, 'A-02', 2, 'PLANNED', NULL, NULL, NULL, NULL, 'seed-a02', '2026-10-23 12:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (3, 1, 'B-01', 3, 'WAITING_USER', 'CONFIRM_TARGET', '{"loan_id":1}', NULL, NULL, 'seed-b01', '2026-10-23 12:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (4, 1, 'B-02', 4, 'PLANNED', NULL, '{"housing_account_id":1}', NULL, NULL, 'seed-b02', '2026-10-23 12:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (5, 2, 'C-01', 1, 'HANDED_OFF', NULL, '{"loan_id":2}', NULL, NULL, 'seed-c01', '2026-10-22 10:00:00.000000', NULL, 0, '2026-10-22 10:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (6, 3, 'C-02', 1, 'HANDED_OFF', NULL, '{"decedent_id":1}', NULL, NULL, 'seed-c02', '2026-10-21 09:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');

INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `resume_step`, `draft_data`, `failure_reason`, `cancel_reason`, `request_id`, `last_activity_at`, `reset_at`, `version`, `created_at`) VALUES (7, 3, 'B-02', 2, 'DONE', NULL, '{"housing_account_id":2}', NULL, NULL, 'seed-b02-history', '2026-10-21 09:00:00.000000', NULL, 0, '2026-10-21 09:00:00.000000');



-- task_result

INSERT INTO `task_result` (`id`, `work_item_id`, `result_type`, `decision_code`, `target_loan_id`, `rule_version`, `basis_doc_id`, `before_value`, `after_value`, `detail`, `created_at`) VALUES (1, 1, 'QUERY', 'SUCCESS', NULL, 'MOCK-1', NULL, NULL, NULL, '{"account_id":1,"period":"LAST_1_MONTH","result_is_sample":true}', '2026-10-23 12:00:00.000000');

INSERT INTO `task_result` (`id`, `work_item_id`, `result_type`, `decision_code`, `target_loan_id`, `rule_version`, `basis_doc_id`, `before_value`, `after_value`, `detail`, `created_at`) VALUES (2, 7, 'EXECUTION', 'MOCK_SUCCESS', NULL, 'MOCK-1', NULL, '{"status":"ACTIVE"}', '{"status":"CLOSED"}', '{"housing_account_id":2,"mock":true}', '2026-10-21 09:00:00.000000');



-- consent_record

INSERT INTO `consent_record` (`id`, `work_item_id`, `consent_type`, `notice_version`, `agreed`, `agreed_at`) VALUES (1, 7, 'B02_TERMINATION', 'MOCK-2026-1', 1, '2026-10-21 09:00:00.000000');



-- service_case

INSERT INTO `service_case` (`id`, `work_item_id`, `customer_id`, `case_type`, `case_status`, `review_status`, `revision`, `previous_case_id`, `loan_id`, `decedent_id`, `visit_required`, `application`, `assigned_staff_id`, `submitted_at`, `review_started_at`, `decided_at`, `result_notified_at`, `canceled_at`, `version`, `created_at`, `updated_at`) VALUES (1, 5, 1, 'C-01', 'IN_REVIEW', 'CHECKED', 1, NULL, 2, NULL, NULL, '{"reasons":["INCOME_DECREASE"],"adjustment_plan_possible":false,"narrative":"가상의 소득 감소로 상담 희망","is_mock":true}', 101, '2026-10-22 10:00:00.000000', '2026-10-23 12:00:00.000000', NULL, NULL, NULL, 0, '2026-10-22 10:00:00.000000', '2026-10-23 12:00:00.000000');

INSERT INTO `service_case` (`id`, `work_item_id`, `customer_id`, `case_type`, `case_status`, `review_status`, `revision`, `previous_case_id`, `loan_id`, `decedent_id`, `visit_required`, `application`, `assigned_staff_id`, `submitted_at`, `review_started_at`, `decided_at`, `result_notified_at`, `canceled_at`, `version`, `created_at`, `updated_at`) VALUES (2, 6, 1, 'C-02', 'SUBMITTED', 'CHECKED', 1, NULL, NULL, 1, NULL, '{"has_inheritance_dispute":false,"applicant_name":"김민수","is_mock":true}', 101, '2026-10-21 09:00:00.000000', NULL, NULL, NULL, NULL, 0, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');



-- heir

INSERT INTO `heir` (`id`, `case_id`, `person_ref`, `name`, `birth_date`, `relationship`, `is_applicant`, `visit_status`, `delegation_method`, `delegation_receipt_no`, `customer_confirmed_at`, `version`) VALUES (1, 2, 'H-001', '김민수', '1997-04-12', '자녀', 1, 'WILL_VISIT', NULL, NULL, '2026-10-21 09:00:00.000000', 0);

INSERT INTO `heir` (`id`, `case_id`, `person_ref`, `name`, `birth_date`, `relationship`, `is_applicant`, `visit_status`, `delegation_method`, `delegation_receipt_no`, `customer_confirmed_at`, `version`) VALUES (2, 2, 'H-002', '김하나', '1994-01-03', '자녀', 0, 'WILL_NOT_VISIT', 'DELEGATION', 'MOCK-DEL-001', '2026-10-21 09:00:00.000000', 0);



-- case_revision

INSERT INTO `case_revision` (`id`, `case_id`, `revision`, `application_snapshot`, `heir_snapshot`, `document_manifest`, `change_reason`, `created_by_user_id`, `created_at`) VALUES (1, 1, 1, '{"reasons":["INCOME_DECREASE"],"adjustment_plan_possible":false,"is_mock":true}', NULL, '[{"document_file_id":1},{"document_file_id":2}]', 'INITIAL_SUBMISSION', 1, '2026-10-22 10:00:00.000000');

INSERT INTO `case_revision` (`id`, `case_id`, `revision`, `application_snapshot`, `heir_snapshot`, `document_manifest`, `change_reason`, `created_by_user_id`, `created_at`) VALUES (2, 2, 1, '{"has_inheritance_dispute":false,"is_mock":true}', '[{"name":"김민수","is_applicant":true},{"name":"김하나","delegation_receipt_no":"MOCK-DEL-001"}]', '[{"document_file_id":3}]', 'INITIAL_SUBMISSION', 1, '2026-10-21 09:00:00.000000');



-- case_statement

INSERT INTO `case_statement` (`id`, `case_id`, `revision`, `source_type`, `source_message_id`, `summary_text`, `confirmed_by_customer`, `source_at`, `captured_at`, `expires_at`) VALUES (1, 1, 1, 'CUSTOMER', NULL, '고객은 최근 소득 감소를 이유로 채무조정 상담을 희망함.', 1, '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000', '2026-11-21 10:00:00');

INSERT INTO `case_statement` (`id`, `case_id`, `revision`, `source_type`, `source_message_id`, `summary_text`, `confirmed_by_customer`, `source_at`, `captured_at`, `expires_at`) VALUES (2, 2, 1, 'CUSTOMER', NULL, '고객은 공동상속인 1명의 위임으로 상속예금 사전접수를 진행함.', 1, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000', '2026-11-20 09:00:00');



-- document_type

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('B01_REQUEST', '변제 충당순서 변경 신청서', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C01_REQUEST', '채무조정 요청서', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C01_PRIVACY', '개인신용정보 동의서', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C01_PLAN', '채무조정안(작성 곤란 시 생략 가능)', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C01_INCOME', '소득감소 입증자료', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C02_FAMILY', '가족관계 확인자료', 'UPLOAD', NULL, 0, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C02_DELEGATION', '상속 위임 관련 서류', 'UPLOAD', NULL, 1, 'MOCK', NULL, 0, 1, 1);

INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES ('C02_ID', '상속인 신분 확인자료', 'UPLOAD', NULL, 1, 'MOCK', NULL, 0, 1, 1);



-- required_document

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (1, 5, 'C01_REQUEST', NULL, 'REQUIRED', NULL, 'MOCK-1', 'UPLOADED', NULL, NULL, NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (2, 5, 'C01_PRIVACY', NULL, 'REQUIRED', NULL, 'MOCK-1', 'UPLOADED', NULL, NULL, NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (3, 5, 'C01_PLAN', NULL, 'OPTIONAL', '고객이 작성 곤란 선택', 'MOCK-1', 'NOT_UPLOADED', NULL, NULL, NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (4, 5, 'C01_INCOME', NULL, 'REQUIRED', '소득 감소 사유', 'MOCK-1', 'NOT_UPLOADED', NULL, NULL, NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (5, 6, 'C02_FAMILY', NULL, 'REQUIRED', NULL, 'MOCK-1', 'UPLOADED', NULL, NULL, NULL, '2026-10-21 09:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (6, 6, 'C02_DELEGATION', 2, 'CONDITIONAL', '상속인 김하나 불참 및 위임', 'MOCK-1', 'NOT_UPLOADED', NULL, NULL, NULL, '2026-10-21 09:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (7, 6, 'C02_ID', 1, 'VISIT_REQUIRED', '방문 시 신분 확인', 'MOCK-1', 'NOT_UPLOADED', NULL, NULL, NULL, '2026-10-21 09:00:00.000000');

INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `human_checked_by`, `human_checked_at`, `human_check_note`, `created_at`) VALUES (8, 3, 'B01_REQUEST', NULL, 'REQUIRED', NULL, 'MOCK-1', 'NOT_UPLOADED', NULL, NULL, NULL, '2026-10-21 09:00:00.000000');



-- document_file

INSERT INTO `document_file` (`id`, `required_document_id`, `version`, `is_latest`, `original_name`, `extension`, `size_bytes`, `storage_path`, `issued_date`, `submitter`, `uploaded_by`, `uploaded_at`, `deleted_at`) VALUES (1, 1, 1, 1, 'c01_request_demo.pdf', 'pdf', 1433, '/mock-fixtures/c01_request_demo.pdf', '2026-10-20', '김민수', 1, '2026-10-22 10:00:00.000000', NULL);

INSERT INTO `document_file` (`id`, `required_document_id`, `version`, `is_latest`, `original_name`, `extension`, `size_bytes`, `storage_path`, `issued_date`, `submitter`, `uploaded_by`, `uploaded_at`, `deleted_at`) VALUES (2, 2, 1, 1, 'c01_privacy_demo.png', 'png', 6378, '/mock-fixtures/c01_privacy_demo.png', '2026-10-20', '김민수', 1, '2026-10-22 10:00:00.000000', NULL);

INSERT INTO `document_file` (`id`, `required_document_id`, `version`, `is_latest`, `original_name`, `extension`, `size_bytes`, `storage_path`, `issued_date`, `submitter`, `uploaded_by`, `uploaded_at`, `deleted_at`) VALUES (3, 5, 1, 1, 'c02_family_demo.jpg', 'jpg', 19610, '/mock-fixtures/c02_family_demo.jpg', '2026-10-19', '김민수', 1, '2026-10-21 09:00:00.000000', NULL);



-- document_extraction

INSERT INTO `document_extraction` (`id`, `document_file_id`, `method`, `status`, `detected_type`, `raw_text_path`, `confidence`, `fields`, `model_version`, `prompt_version`, `failure_reason`, `started_at`, `finished_at`) VALUES (1, 1, 'TEXT_PARSER', 'SUCCESS', 'C01_REQUEST', NULL, 0.97, '{"name":"김민수","signature_detected":true}', 'MOCK-EXTRACTOR', 'MOCK-PROMPT', NULL, '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000');

INSERT INTO `document_extraction` (`id`, `document_file_id`, `method`, `status`, `detected_type`, `raw_text_path`, `confidence`, `fields`, `model_version`, `prompt_version`, `failure_reason`, `started_at`, `finished_at`) VALUES (2, 2, 'CLOVA_OCR', 'SUCCESS', 'C01_PRIVACY', NULL, 0.92, '{"name":"김민수","signature_detected":true}', 'MOCK-OCR', 'MOCK-PROMPT', NULL, '2026-10-22 10:00:00.000000', '2026-10-22 10:00:00.000000');

INSERT INTO `document_extraction` (`id`, `document_file_id`, `method`, `status`, `detected_type`, `raw_text_path`, `confidence`, `fields`, `model_version`, `prompt_version`, `failure_reason`, `started_at`, `finished_at`) VALUES (3, 3, 'CLOVA_OCR', 'SUCCESS', 'C02_FAMILY', NULL, 0.88, '{"decedent":"김영식","heirs":["김민수","김하나"]}', 'MOCK-OCR', 'MOCK-PROMPT', NULL, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');



-- agent_review_run

INSERT INTO `agent_review_run` (`id`, `case_id`, `revision`, `check_type`, `status`, `attempt_count`, `model_version`, `prompt_version`, `rule_version`, `failure_reason`, `report`, `started_at`, `finished_at`) VALUES (1, 1, 1, 'DOCUMENT_PRECHECK', 'COMPLETED', 1, 'MOCK-AGENT', 'MOCK-PROMPT', 'MOCK-1', NULL, '{"warning":"소득감소 입증자료 미첨부","human_decision_required":true}', '2026-10-22 10:00:00.000000', '2026-10-23 12:00:00.000000');

INSERT INTO `agent_review_run` (`id`, `case_id`, `revision`, `check_type`, `status`, `attempt_count`, `model_version`, `prompt_version`, `rule_version`, `failure_reason`, `report`, `started_at`, `finished_at`) VALUES (2, 2, 1, 'DOCUMENT_PRECHECK', 'COMPLETED', 1, 'MOCK-AGENT', 'MOCK-PROMPT', 'MOCK-1', NULL, '{"warning":"위임서류 원본 확인 필요","human_decision_required":true}', '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');



-- review_item

INSERT INTO `review_item` (`id`, `case_id`, `revision`, `agent_review_run_id`, `source`, `category`, `result`, `rule_id`, `rule_version`, `basis_doc_id`, `document_file_id`, `heir_id`, `message`, `evidence`, `created_at`) VALUES (1, 1, 1, 1, 'SPRING', 'DOCUMENT', 'NEEDS_CHECK', 'MOCK-REQ-INCOME', 'MOCK-1', NULL, NULL, NULL, '소득감소 입증자료가 아직 첨부되지 않았습니다.', '{"required_document_id":4}', '2026-10-23 12:00:00.000000');

INSERT INTO `review_item` (`id`, `case_id`, `revision`, `agent_review_run_id`, `source`, `category`, `result`, `rule_id`, `rule_version`, `basis_doc_id`, `document_file_id`, `heir_id`, `message`, `evidence`, `created_at`) VALUES (2, 2, 1, 2, 'AGENT', 'DELEGATION', 'NEEDS_CHECK', NULL, NULL, NULL, 3, 2, '상속인 위임 내용과 원본 서류를 직원이 확인해야 합니다.', '{"mock":true}', '2026-10-21 09:00:00.000000');



-- clarification

INSERT INTO `clarification` (`id`, `case_id`, `review_item_id`, `requested_revision`, `kind`, `question`, `status`, `created_by_run_id`, `due_at`, `answer_text`, `confirmed_at`, `answer_file_id`, `result_revision`, `nonresponse_visit_assumption`, `created_at`) VALUES (1, 1, 1, 1, 'FACT_CHECK', '소득감소 기간을 확인해 주세요.', 'PENDING', 1, '2026-10-30 17:00:00', NULL, NULL, NULL, NULL, NULL, '2026-10-23 12:00:00.000000');



-- staff_review

INSERT INTO `staff_review` (`id`, `case_id`, `staff_id`, `revision`, `started_at`, `decided_at`, `result`, `opinion_draft`, `opinion_final`, `follow_up_final`, `adjustment`, `version`) VALUES (1, 1, 101, 1, '2026-10-23 12:00:00.000000', NULL, NULL, '소득감소 입증자료 확인 필요', NULL, NULL, NULL, 0);



-- notification

INSERT INTO `notification` (`id`, `customer_id`, `case_id`, `work_item_id`, `task_result_id`, `type`, `title`, `content`, `dedup_key`, `read_at`, `email_status`, `email_attempts`, `email_last_error`, `email_sent_at`, `created_at`) VALUES (1, 1, 1, NULL, NULL, 'CASE_SUBMITTED', '채무조정 사전접수 완료', 'C-01 사전접수가 완료되었습니다.', 'CASE1-SUBMIT', NULL, 'PENDING', 0, NULL, NULL, '2026-10-22 10:00:00.000000');

INSERT INTO `notification` (`id`, `customer_id`, `case_id`, `work_item_id`, `task_result_id`, `type`, `title`, `content`, `dedup_key`, `read_at`, `email_status`, `email_attempts`, `email_last_error`, `email_sent_at`, `created_at`) VALUES (2, 1, 2, NULL, NULL, 'CASE_SUBMITTED', '상속예금 사전접수 완료', 'C-02 사전접수가 완료되었습니다.', 'CASE2-SUBMIT', '2026-10-21 09:00:00.000000', 'SENT', 1, NULL, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');



-- branch_slot

INSERT INTO `branch_slot` (`id`, `branch_id`, `start_at`, `end_at`, `capacity`, `reserved_count`, `status`, `version`) VALUES (1, 1, '2026-10-27 01:00:00', '2026-10-27 01:30:00', 3, 1, 'OPEN', 0);

INSERT INTO `branch_slot` (`id`, `branch_id`, `start_at`, `end_at`, `capacity`, `reserved_count`, `status`, `version`) VALUES (2, 1, '2026-10-27 02:00:00', '2026-10-27 02:30:00', 1, 1, 'FULL', 0);

INSERT INTO `branch_slot` (`id`, `branch_id`, `start_at`, `end_at`, `capacity`, `reserved_count`, `status`, `version`) VALUES (3, 1, '2026-10-28 01:00:00', '2026-10-28 01:30:00', 2, 0, 'OPEN', 0);



-- staff_slot_availability

INSERT INTO `staff_slot_availability` (`id`, `staff_id`, `branch_slot_id`, `available`, `max_assignments`, `assigned_count`, `version`) VALUES (1, 101, 1, 1, 2, 1, 0);

INSERT INTO `staff_slot_availability` (`id`, `staff_id`, `branch_slot_id`, `available`, `max_assignments`, `assigned_count`, `version`) VALUES (2, 101, 2, 0, 1, 1, 0);

INSERT INTO `staff_slot_availability` (`id`, `staff_id`, `branch_slot_id`, `available`, `max_assignments`, `assigned_count`, `version`) VALUES (3, 101, 3, 1, 2, 0, 0);



-- visit_reservation

INSERT INTO `visit_reservation` (`id`, `case_id`, `branch_slot_id`, `status`, `assigned_staff_id`, `staff_availability_id`, `recommended_staff_id`, `assignment_reason`, `assignment_model_version`, `assignment_attempt_count`, `request_id`, `selected_at`, `confirmed_at`, `canceled_at`, `expires_at`, `version`) VALUES (1, 2, 1, 'CONFIRMED', 101, 1, 101, '{"task_code":"C-02","capability_matched":true,"mock":true}', 'MOCK-RECOMMENDER', 1, 'seed-booking-c02', '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000', NULL, '2026-10-27 01:00:00', 0);



-- event_log

INSERT INTO `event_log` (`id`, `entity_type`, `entity_id`, `event_type`, `from_value`, `to_value`, `actor_type`, `actor_id`, `request_id`, `payload`, `created_at`) VALUES (1, 'WORK_ITEM', '1', 'QUERY_COMPLETED', 'ACTIVE', 'DONE', 'SYSTEM', NULL, 'seed-a01', '{"task_code":"A-01"}', '2026-10-23 12:00:00.000000');

INSERT INTO `event_log` (`id`, `entity_type`, `entity_id`, `event_type`, `from_value`, `to_value`, `actor_type`, `actor_id`, `request_id`, `payload`, `created_at`) VALUES (2, 'SERVICE_CASE', '1', 'CASE_SUBMITTED', 'DRAFT', 'SUBMITTED', 'CUSTOMER', 1, 'seed-c01', '{"task_code":"C-01"}', '2026-10-22 10:00:00.000000');

INSERT INTO `event_log` (`id`, `entity_type`, `entity_id`, `event_type`, `from_value`, `to_value`, `actor_type`, `actor_id`, `request_id`, `payload`, `created_at`) VALUES (3, 'SERVICE_CASE', '1', 'REVIEW_STARTED', 'SUBMITTED', 'IN_REVIEW', 'STAFF', 101, 'seed-c01-review', NULL, '2026-10-23 12:00:00.000000');

INSERT INTO `event_log` (`id`, `entity_type`, `entity_id`, `event_type`, `from_value`, `to_value`, `actor_type`, `actor_id`, `request_id`, `payload`, `created_at`) VALUES (4, 'VISIT_RESERVATION', '1', 'RESERVATION_CONFIRMED', 'PENDING_ASSIGNMENT', 'CONFIRMED', 'SYSTEM', NULL, 'seed-booking-c02', '{"staff_id":101}', '2026-10-21 09:00:00.000000');



COMMIT;
