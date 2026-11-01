package com.bankingax.task.c02;
import java.util.Map;
public interface OcrPort { OcrResult extract(ExtractRequest request); record ExtractRequest(long documentFileId,String documentType,String storageReference){} record OcrResult(String method,String status,Map<String,Object> fields,String failureReason){} }
