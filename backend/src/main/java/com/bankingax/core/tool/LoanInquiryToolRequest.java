package com.bankingax.core.tool;

public record LoanInquiryToolRequest(String requestId, Long workItemId, long customerId, Long loanId) { }
