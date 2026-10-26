package com.bankingax.task.a01;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Database-query parameters after service-level validation and ownership checks. */
public record TransactionQuery(long accountId, LocalDateTime startInclusive, LocalDateTime endExclusive,
                               TransactionDirection direction, BigDecimal minimumAmount, BigDecimal maximumAmount,
                               String keyword, TransactionSortOrder sortOrder, long offset, int size) { }
