package com.bankingax.task.a01;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolActor;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** A-01 deterministic query boundary. It has no account mutation path. */
@Service
public class TransactionSearchService {
    private static final ZoneId MOCK_TIME_ZONE = ZoneId.of("Asia/Seoul");
    private final TransactionQueryPort transactions;
    public TransactionSearchService(TransactionQueryPort transactions) { this.transactions = transactions; }

    public TransactionSearchResponse search(ToolActor actor, TransactionSearchRequest request) {
        if (actor.role() != ActorRole.CUSTOMER || actor.actorId() != request.customerId())
            throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        if (request.requestId() == null || request.requestId().isBlank()) throw new DomainException("INVALID_REQUEST", "requestId is required.");
        if (request.startDate() == null || request.endDate() == null || request.startDate().isAfter(request.endDate()))
            throw new DomainException("INVALID_DATE_RANGE", "startDate must be on or before endDate.");
        int page = request.page() == null ? 0 : request.page(); int size = request.size() == null ? 20 : request.size();
        if (page < 0 || size <= 0) throw new DomainException("INVALID_PAGINATION", "page must be non-negative and size must be positive.");
        TransactionAccount account = transactions.findAccount(request.accountId()).orElseThrow(() -> new DomainException("NOT_FOUND", "Account was not found."));
        if (account.customerId() != request.customerId()) throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        long offset;
        try { offset = Math.multiplyExact((long) page, size); } catch (ArithmeticException exception) { throw new DomainException("INVALID_PAGINATION", "page is too large."); }
        List<TransactionSearchResponse.TransactionView> filtered = transactions.findTransactions(account.accountId()).stream()
            .filter(item -> !item.occurredAt().toLocalDate().isBefore(request.startDate()) && !item.occurredAt().toLocalDate().isAfter(request.endDate()))
            .filter(item -> request.transactionType() == null || item.direction() == request.transactionType())
            .sorted(Comparator.comparing(TransactionRecord::occurredAt).reversed().thenComparing(TransactionRecord::transactionId, Comparator.reverseOrder()))
            .map(item -> new TransactionSearchResponse.TransactionView(item.transactionId(), item.occurredAt(), item.direction(), item.amount(), item.counterparty(), item.memo())).toList();
        List<TransactionSearchResponse.TransactionView> content = filtered.stream().skip(offset).limit(size).toList();
        int pages = filtered.isEmpty() ? 0 : (int) ((filtered.size() + (long) size - 1) / size);
        return new TransactionSearchResponse(account.accountId(), account.accountNoMasked(), account.accountName(), MOCK_TIME_ZONE.getId(), content, page, size, filtered.size(), pages);
    }
}
