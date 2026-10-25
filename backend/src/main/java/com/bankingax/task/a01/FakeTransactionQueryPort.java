package com.bankingax.task.a01;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic non-production fixture. It never connects to a bank or database. */
@Component
@Profile("!production")
public class FakeTransactionQueryPort implements TransactionQueryPort {
    private static final TransactionAccount CUSTOMER_ONE = new TransactionAccount(1001L, 1L, "110-***-000001", "AX 입출금 통장", true);
    private static final TransactionAccount CUSTOMER_TWO = new TransactionAccount(2001L, 2L, "110-***-000002", "AX 입출금 통장", true);
    private static final List<TransactionRecord> RECORDS = List.of(
        new TransactionRecord(5L, 1001L, LocalDateTime.of(2026, 10, 20, 15, 30), TransactionDirection.OUT, new BigDecimal("12500.00"), "AX마트", "생활비"),
        new TransactionRecord(4L, 1001L, LocalDateTime.of(2026, 10, 20, 9, 0), TransactionDirection.IN, new BigDecimal("2500000.00"), "AX급여", "급여"),
        new TransactionRecord(3L, 1001L, LocalDateTime.of(2026, 10, 3, 18, 45), TransactionDirection.OUT, new BigDecimal("4300.00"), "AX카페", "식비"),
        new TransactionRecord(2L, 1001L, LocalDateTime.of(2026, 9, 30, 10, 0), TransactionDirection.IN, new BigDecimal("100000.00"), "AX송금", "용돈"),
        new TransactionRecord(1L, 2001L, LocalDateTime.of(2026, 10, 21, 12, 0), TransactionDirection.IN, new BigDecimal("1.00"), "다른 고객", "격리 검증")
    );
    @Override public Optional<TransactionAccount> findAccount(long accountId) {
        if (accountId == CUSTOMER_ONE.accountId()) return Optional.of(CUSTOMER_ONE);
        if (accountId == CUSTOMER_TWO.accountId()) return Optional.of(CUSTOMER_TWO);
        return Optional.empty();
    }
    @Override public List<TransactionRecord> findTransactions(long accountId) {
        return RECORDS.stream().filter(item -> item.accountId() == accountId).toList();
    }
}
