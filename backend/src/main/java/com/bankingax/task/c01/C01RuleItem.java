package com.bankingax.task.c01;

public record C01RuleItem(String ruleCode, String code, String disposition, String message) {
    static C01RuleItem blocked(String ruleCode, String code, String message) { return new C01RuleItem(ruleCode, code, "BLOCKED", message); }
    static C01RuleItem staff(String ruleCode, String code, String message) { return new C01RuleItem(ruleCode, code, "NEEDS_STAFF_REVIEW", message); }
}
