package com.bankingax.core.tool;

/** Executes a loan inquiry or replays its persisted result according to the configured workflow store. */
public interface LoanInquiryExecutor {
    LoanInquiryToolResponse execute(LoanInquiryToolRequest request);
}
