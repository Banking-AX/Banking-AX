package com.bankingax.task.a01;

import java.util.List;

public record TransactionPage(List<TransactionRecord> records, long totalElements) { }
