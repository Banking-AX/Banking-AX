package com.bankingax.task.a01;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** account_transaction projection. LocalDateTime follows the mock DDL's DATETIME(6) semantics. */
public record TransactionRecord(long transactionId, long accountId, LocalDateTime occurredAt,
                                TransactionDirection direction, BigDecimal amount,
                                String counterparty, String memo) { }
