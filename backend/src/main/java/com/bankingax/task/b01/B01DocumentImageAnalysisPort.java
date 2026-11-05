package com.bankingax.task.b01;

interface B01DocumentImageAnalysisPort {
    B01OcrPort.VisualChecks analyze(AnalysisRequest request);

    record AnalysisRequest(String requestId, String fileName, String contentType, byte[] content) { }
}
