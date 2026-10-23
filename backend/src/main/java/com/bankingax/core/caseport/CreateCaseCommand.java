package com.bankingax.core.caseport;

import com.bankingax.core.workflow.TaskCode;
import java.util.Map;

public record CreateCaseCommand(long actorId, long workItemId, TaskCode taskCode, Long loanId,
                                Map<String, Object> application) { }
