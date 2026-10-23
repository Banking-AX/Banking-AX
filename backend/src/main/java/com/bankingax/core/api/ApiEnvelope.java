package com.bankingax.core.api;

public record ApiEnvelope<T>(String requestId, String taskCode, Long workItemId, Long caseId,
                             Integer revision, T data, ApiError error) {
    public static <T> ApiEnvelope<T> success(String requestId, String taskCode, Long workItemId,
                                               Long caseId, Integer revision, T data) {
        return new ApiEnvelope<>(requestId, taskCode, workItemId, caseId, revision, data, null);
    }
}
