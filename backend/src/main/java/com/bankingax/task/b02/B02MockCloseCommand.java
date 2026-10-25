package com.bankingax.task.b02;

/** Boundary for the development-only Mock close command. It never calls a bank system. */
public interface B02MockCloseCommand {
    void close(long accountId);
}
