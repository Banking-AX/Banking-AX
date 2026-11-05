-- C-02 OCR policy: only the reviewed official delegation form is eligible.
-- General family/basic certificates remain OCR-excluded and are reviewed by staff.
UPDATE document_type
SET source_kind = 'SHINHAN_OFFICIAL',
    source_doc_id = 'C02-02',
    source_verified = TRUE,
    ocr_enabled = TRUE
WHERE code = 'ABSENT_HEIR_POWER_OF_ATTORNEY';
