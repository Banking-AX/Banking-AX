package com.bankingax.task.a01;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolActor;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TransactionSearchServiceTest {
    private final TransactionSearchService service = new TransactionSearchService(new FakeTransactionQueryPort());
    private TransactionSearchRequest request(TransactionDirection type, int page, int size) {
        return new TransactionSearchRequest("a01-test", 1001L, 1L, 1001L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), type, page, size);
    }
    @Test void filters_sorts_and_pages_with_decimal_amounts() {
        var result = service.search(new ToolActor(1L, ActorRole.CUSTOMER), request(null, 0, 2));
        assertEquals(3, result.totalElements()); assertEquals(2, result.totalPages());
        assertEquals(5L, result.transactions().get(0).transactionId());
        assertEquals(4L, result.transactions().get(1).transactionId());
        assertEquals("12500.00", result.transactions().get(0).amount().toPlainString());
        assertEquals("Asia/Seoul", result.timeZone());
        assertEquals(3L, service.search(new ToolActor(1L, ActorRole.CUSTOMER), request(null, 1, 2)).transactions().get(0).transactionId());
    }
    @Test void filters_direction_and_returns_empty_result() {
        assertEquals(1, service.search(new ToolActor(1L, ActorRole.CUSTOMER), request(TransactionDirection.IN, 0, 20)).totalElements());
        var empty = service.search(new ToolActor(1L, ActorRole.CUSTOMER), new TransactionSearchRequest("empty", 1L, 1L, 1001L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2), null, 0, 20));
        assertTrue(empty.empty()); assertEquals(0, empty.totalPages());
    }
    @Test void rejects_invalid_date_unknown_account_and_cross_customer_access() {
        assertThrows(DomainException.class, () -> service.search(new ToolActor(1L, ActorRole.CUSTOMER), new TransactionSearchRequest("bad", 1L, 1L, 1001L, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null, 0, 1)));
        assertThrows(DomainException.class, () -> service.search(new ToolActor(1L, ActorRole.CUSTOMER), new TransactionSearchRequest("missing", 1L, 1L, 9999L, LocalDate.now(), LocalDate.now(), null, 0, 1)));
        assertThrows(DomainException.class, () -> service.search(new ToolActor(1L, ActorRole.CUSTOMER), new TransactionSearchRequest("foreign", 1L, 1L, 2001L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, 0, 1)));
        assertThrows(DomainException.class, () -> service.search(new ToolActor(2L, ActorRole.CUSTOMER), request(null, 0, 1)));
    }
}
