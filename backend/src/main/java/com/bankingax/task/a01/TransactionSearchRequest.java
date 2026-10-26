package com.bankingax.task.a01;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionSearchRequest(String requestId, Long workItemId, long customerId, long accountId,
                                       LocalDate startDate, LocalDate endDate,
                                       TransactionDirection transactionType, Integer page, Integer size,
                                       BigDecimal minimumAmount, BigDecimal maximumAmount,
                                       String keyword, TransactionSortOrder sortOrder) {
    public TransactionSearchRequest(String requestId, Long workItemId, long customerId, long accountId,
                                    LocalDate startDate, LocalDate endDate, TransactionDirection transactionType,
                                    Integer page, Integer size) {
        this(requestId,workItemId,customerId,accountId,startDate,endDate,transactionType,page,size,null,null,null,null);
    }
}
