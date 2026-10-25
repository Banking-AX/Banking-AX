package com.bankingax.task.a01;

import java.util.List;
import java.util.Optional;

/** Replace with a T1-owned JPA adapter once common persistence is available. */
public interface TransactionQueryPort {
    Optional<TransactionAccount> findAccount(long accountId);
    List<TransactionRecord> findTransactions(long accountId);
}
