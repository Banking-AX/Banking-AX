package com.bankingax.core.tool;

import com.bankingax.task.a02.LoanSummary;
import java.util.List;

public record LoanInquiryToolResponse(List<LoanSummary> loans) { }
