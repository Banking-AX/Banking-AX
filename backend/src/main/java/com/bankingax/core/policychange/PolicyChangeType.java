package com.bankingax.core.policychange;

/** Change kinds Banking-AX understands. Anything else is quarantined, never ignored or applied. */
public enum PolicyChangeType {
    INFORMATION_UPDATE(false),
    REVIEW_CRITERIA_CHANGE(false),
    ELIGIBILITY_RULE_CHANGE(true),
    DOCUMENT_REQUIREMENT_CHANGE(true),
    PROCEDURE_CHANGE(true);

    private final boolean ruleChangeReviewRequired;

    PolicyChangeType(boolean ruleChangeReviewRequired) { this.ruleChangeReviewRequired = ruleChangeReviewRequired; }

    /** True when adopting the change would alter Java rules or Workflow, which only a code change may do. */
    public boolean ruleChangeReviewRequired() { return ruleChangeReviewRequired; }
}
