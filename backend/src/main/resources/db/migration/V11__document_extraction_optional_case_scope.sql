-- document_extraction is shared by WorkItem-owned B documents and Case-owned C documents.
-- C extractions keep both case columns; B extractions use neither and remain linked through document_file_id.
ALTER TABLE document_extraction
  MODIFY COLUMN case_id BIGINT UNSIGNED NULL,
  MODIFY COLUMN revision INT UNSIGNED NULL,
  ADD CONSTRAINT ck_extraction_case_revision_pair CHECK (
    (case_id IS NULL AND revision IS NULL)
    OR (case_id IS NOT NULL AND revision IS NOT NULL)
  );
