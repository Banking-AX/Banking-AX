package com.bankingax.task.c02;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/**
 * C-02's document-metadata boundary.  It deliberately does not accept file
 * bytes: a storage adapter must upload and validate the bytes before it calls
 * this port with a storage reference.
 */
public interface DocumentPort {
    DocumentMetadata register(DocumentRegistration command);
    List<DocumentMetadata> findByCase(long caseId);
    default Optional<DocumentMetadata> findById(long documentFileId) { return Optional.empty(); }
    /**
     * An upload creates the next Case revision.  Persist the file against that
     * revision so a current staff-precheck snapshot cannot accidentally read a
     * prior revision's extraction.
     */
    default DocumentMetadata bindToCaseRevision(long documentFileId, int caseRevision) {
        return findById(documentFileId).orElseThrow();
    }
    /** Compensation hook used when Case revision cannot be recorded after metadata registration. */
    void delete(long documentFileId);
    C02IntakeService.DocumentStatus status(long caseId, String documentSlotKey);

    record DocumentRegistration(long caseId, int caseRevision, String documentSlotKey,
                                String fileName, String mimeType, long sizeBytes,
                                String storageReference, C02IntakeService.DocumentStatus status,
                                String documentType, String checksum, Instant uploadedAt, Long uploadedBy) {
        public DocumentRegistration(long caseId, int caseRevision, String documentSlotKey,
                                    String fileName, String mimeType, long sizeBytes,
                                    String storageReference, C02IntakeService.DocumentStatus status) {
            this(caseId, caseRevision, documentSlotKey, fileName, mimeType, sizeBytes,
                storageReference, status, documentType(documentSlotKey), null, Instant.now(), null);
        }
        private static String documentType(String slot) {
            int delimiter = slot == null ? -1 : slot.indexOf(':');
            return delimiter < 0 ? slot : slot.substring(0, delimiter);
        }
    }
    record DocumentMetadata(long documentFileId, long caseId, int caseRevision,
                            String documentSlotKey, int version, String fileName,
                            String mimeType, long sizeBytes, String storageReference,
                            C02IntakeService.DocumentStatus status, String documentType,
                            String checksum, Instant uploadedAt, Long uploadedBy) {}
}
