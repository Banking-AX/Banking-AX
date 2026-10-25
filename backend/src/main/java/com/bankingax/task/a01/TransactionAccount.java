package com.bankingax.task.a01;

/** Mock deposit_account projection; account numbers are already masked at this boundary. */
public record TransactionAccount(long accountId, long customerId, String accountNoMasked, String accountName, boolean active) { }
