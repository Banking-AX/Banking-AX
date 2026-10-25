package com.bankingax.task.b02;

import org.springframework.stereotype.Component;

/** The account state remains in B02Service's in-memory fixture. */
@Component
public final class InMemoryB02MockCloseCommand implements B02MockCloseCommand {
    @Override public void close(long accountId) { }
}
