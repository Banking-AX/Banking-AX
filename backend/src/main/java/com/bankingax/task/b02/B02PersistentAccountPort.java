package com.bankingax.task.b02;

import java.util.Optional;

/**
 * Optional database boundary. The default B-02 fixture deliberately does not provide this port.
 * A database adapter owns account locking, state mutation, and durable request replay.
 */
interface B02PersistentAccountPort {
    B02Account find(long accountId);
    Optional<B02Service.R> replay(B02Service.C command);
    B02Service.R close(B02Service.C command);
}
