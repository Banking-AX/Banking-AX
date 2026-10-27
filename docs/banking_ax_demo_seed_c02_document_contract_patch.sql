-- C-02 demo-seed replacement fragment (proposal only; not a Flyway migration).
--
-- Apply this by replacing the matching C-02 rows in banking_ax_demo_seed.sql
-- before loading that seed into a fresh database.  It intentionally keeps
-- service_case.id=2, work_item.id=6, heir.id=1/2, required_document.id=5/6/7,
-- and document_file.id=3; therefore the existing C-02 case/file links remain.
-- The complete seed also contains non-C-02 enum values that must be aligned
-- with the canonical DDL by its owner before it can be loaded as one script.

-- Replace C-02 parent rows only where the current source uses legacy enums.
INSERT INTO `work_item` (`id`, `workflow_id`, `task_code`, `sequence_no`, `status`, `request_id`, `last_activity_at`, `version`, `created_at`)
VALUES (6, 3, 'INHERITANCE_DEPOSIT', 1, 'HANDED_OFF', 'seed-c02', '2026-10-21 09:00:00.000000', 0, '2026-10-21 09:00:00.000000');

INSERT INTO `service_case` (`id`, `work_item_id`, `customer_id`, `case_type`, `case_status`, `review_status`, `revision`, `decedent_id`, `visit_required`, `application`, `assigned_staff_id`, `submitted_at`, `version`, `created_at`, `updated_at`)
VALUES (2, 6, 1, 'INHERITANCE_DEPOSIT', 'SUBMITTED', 'READY_FOR_STAFF', 1, 1, 1,
        '{"has_inheritance_dispute":false,"applicant_name":"김민수","is_mock":true}', 101,
        '2026-10-21 09:00:00.000000', 0, '2026-10-21 09:00:00.000000', '2026-10-21 09:00:00.000000');

INSERT INTO `heir` (`id`, `case_id`, `person_ref`, `name`, `birth_date`, `relationship`, `is_applicant`, `visit_status`, `delegation_method`, `delegation_receipt_no`, `customer_confirmed_at`, `version`)
VALUES
  (1, 2, 'H-001', '김민수', '1997-04-12', '자녀', 1, 'ATTENDING', 'NONE', NULL, '2026-10-21 09:00:00.000000', 0),
  (2, 2, 'H-002', '김하나', '1994-01-03', '자녀', 0, 'ABSENT', 'RECEIPT', 'MOCK-DEL-001', '2026-10-21 09:00:00.000000', 0);

-- Replace C02_FAMILY/C02_DELEGATION/C02_ID with one code for each contract slot.
-- These are ordinary supporting documents, so none is an OCR-approved official form.
INSERT INTO `document_type` (`code`, `name`, `submission_kind`, `valid_days`, `per_person`, `source_kind`, `source_doc_id`, `source_verified`, `ocr_enabled`, `is_active`) VALUES
  ('DECEDENT_DETAILED_FAMILY_RELATION', '상세 가족관계증명서', 'UPLOAD', NULL, 0, 'OTHER', NULL, 0, 0, 1),
  ('DECEDENT_BASIC_CERTIFICATE', '기본증명서', 'UPLOAD', NULL, 0, 'OTHER', NULL, 0, 0, 1),
  ('PASSBOOK_AND_SEAL', '통장 및 인감', 'VISIT_ONLY', NULL, 0, 'OTHER', NULL, 0, 0, 1),
  ('DECEDENT_REMOVED_FAMILY_REGISTER', '제적등본', 'UPLOAD', NULL, 0, 'OTHER', NULL, 0, 0, 1),
  ('HEIR_IDENTITY_CARD', '상속인 신분증', 'UPLOAD', NULL, 1, 'OTHER', NULL, 0, 0, 1),
  ('HEIR_SEAL_OR_SIGNATURE', '상속인 인감 또는 서명', 'VISIT_ONLY', NULL, 1, 'OTHER', NULL, 0, 0, 1),
  ('ABSENT_HEIR_ID_COPY', '불참 상속인 신분증 사본', 'UPLOAD', NULL, 1, 'OTHER', NULL, 0, 0, 1),
  ('ABSENT_HEIR_SEAL_CERTIFICATE', '불참 상속인 인감증명서', 'UPLOAD', NULL, 1, 'OTHER', NULL, 0, 0, 1),
  ('ABSENT_HEIR_POWER_OF_ATTORNEY', '불참 상속인 위임장', 'UPLOAD', NULL, 1, 'OTHER', NULL, 0, 0, 1),
  ('DELEGATION_RECEIPT_NO', '위임장 접수번호', 'INPUT_VALUE', NULL, 1, 'OTHER', NULL, 0, 0, 1);

-- Replace only the three C-02 required_document rows and add the two omitted
-- common slots.  Row 5 remains the target of document_file.id=3, preserving
-- the existing file relation.  The absent heir uses the existing valid receipt,
-- so its slot is INPUT_VALUE rather than a replacement uploaded file.
INSERT INTO `required_document` (`id`, `work_item_id`, `document_type_code`, `heir_id`, `requirement`, `condition_note`, `rule_version`, `status`, `created_at`) VALUES
  (5, 6, 'DECEDENT_DETAILED_FAMILY_RELATION', NULL, 'REQUIRED', NULL, 'MOCK-1', 'UPLOADED', '2026-10-21 09:00:00.000000'),
  (6, 6, 'DELEGATION_RECEIPT_NO', 2, 'REQUIRED', '상속인 김하나의 유효한 위임 접수번호', 'MOCK-1', 'NOT_SUBMITTED', '2026-10-21 09:00:00.000000'),
  (7, 6, 'HEIR_IDENTITY_CARD', 1, 'REQUIRED', NULL, 'MOCK-1', 'NOT_SUBMITTED', '2026-10-21 09:00:00.000000'),
  (9, 6, 'DECEDENT_BASIC_CERTIFICATE', NULL, 'REQUIRED', NULL, 'MOCK-1', 'NOT_SUBMITTED', '2026-10-21 09:00:00.000000'),
  (10, 6, 'PASSBOOK_AND_SEAL', NULL, 'VISIT_ONLY', NULL, 'MOCK-1', 'NOT_SUBMITTED', '2026-10-21 09:00:00.000000');

-- Keep the original document_file id=3 row unchanged.  Its required_document_id
-- remains 5, which now identifies DECEDENT_DETAILED_FAMILY_RELATION.
