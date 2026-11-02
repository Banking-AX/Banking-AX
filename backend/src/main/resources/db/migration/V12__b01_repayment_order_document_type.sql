INSERT INTO document_type
  (code, name, submission_kind, source_kind, source_doc_id,
   source_verified, ocr_enabled, is_active)
VALUES
  ('B01_REPAYMENT_ORDER_APPLICATION', '채무변제충당순서 변경 신청서', 'UPLOAD',
   'SHINHAN_OFFICIAL', 'B01-REPAYMENT-ORDER-APPLICATION-v1.7.1',
   TRUE, TRUE, TRUE);
