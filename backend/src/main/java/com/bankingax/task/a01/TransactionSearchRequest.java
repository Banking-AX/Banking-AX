package com.bankingax.task.a01;

import java.time.LocalDate;

public record TransactionSearchRequest(String requestId, Long workItemId, long customerId, long accountId,
                                       LocalDate startDate, LocalDate endDate,
                                       TransactionDirection transactionType, Integer page, Integer size) { }
