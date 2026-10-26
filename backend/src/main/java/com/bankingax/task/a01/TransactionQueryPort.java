package com.bankingax.task.a01;

import java.util.List;
import java.util.Optional;

/** Boundary for the project Mock-financial datastore; it never reaches a real bank system. */
public interface TransactionQueryPort {
    Optional<TransactionAccount> findAccount(long accountId);
    TransactionPage search(TransactionQuery query);
}
