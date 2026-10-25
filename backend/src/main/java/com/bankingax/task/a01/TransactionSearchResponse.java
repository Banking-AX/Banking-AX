package com.bankingax.task.a01;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TransactionSearchResponse(long accountId, String accountNoMasked, String accountName,
                                        String timeZone, List<TransactionView> transactions,
                                        int page, int size, long totalElements, int totalPages) {
    public boolean empty() { return transactions.isEmpty(); }
    public record TransactionView(long transactionId, LocalDateTime occurredAt, TransactionDirection direction,
                                  BigDecimal amount, String counterparty, String memo) { }
}
