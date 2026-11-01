package com.bankingax.core.tool;

import com.bankingax.task.a02.LoanSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanInquiryToolResponse(
        List<LoanSummary> loans, String workItemStatus, String workflowStatus) {
    public LoanInquiryToolResponse(List<LoanSummary> loans) {
        this(loans, null, null);
    }
}
