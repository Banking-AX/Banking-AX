package com.bankingax.task.c01;

import java.util.List;
public record C01RuleEvaluation(boolean submissionAllowed, List<C01RuleItem> items) { }
