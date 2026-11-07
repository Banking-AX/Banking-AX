package com.bankingax.task.c02;

import java.util.Map;

/** Boundary for OCR over bytes that were read from the private C-02 file store. */
public interface OcrPort {
    OcrResult extract(ExtractRequest request);
    record ExtractRequest(long documentFileId, String documentType, String storageReference,
                          String fileName, String contentType, byte[] content) { }
    record OcrResult(String method, String status, Map<String, Object> fields, String failureReason) { }
}
