package com.bankingax.task.c02;

import java.util.List;

/**
 * C-02's document-metadata boundary.  It deliberately does not accept file
 * bytes: a storage adapter must upload and validate the bytes before it calls
 * this port with a storage reference.
 */
public interface DocumentPort {
    DocumentMetadata register(DocumentRegistration command);
    List<DocumentMetadata> findByCase(long caseId);
    C02IntakeService.DocumentStatus status(long caseId, String documentSlotKey);

    record DocumentRegistration(long caseId, int caseRevision, String documentSlotKey,
                                String fileName, String mimeType, long sizeBytes,
                                String storageReference, C02IntakeService.DocumentStatus status) {}
    record DocumentMetadata(long documentFileId, long caseId, int caseRevision,
                            String documentSlotKey, int version, String fileName,
                            String mimeType, long sizeBytes, String storageReference,
                            C02IntakeService.DocumentStatus status) {}
}
