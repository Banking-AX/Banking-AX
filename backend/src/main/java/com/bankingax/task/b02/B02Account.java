package com.bankingax.task.b02;

import java.time.LocalDate;

/** Minimal financial-account projection used by the B-02 execution boundary. */
record B02Account(long id, long customerId, LocalDate joinedAt, boolean subscriptionWon,
                  String status, int version) { }
