package com.bankingax.task.a01;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolActor;
import java.time.ZoneId;
import java.time.LocalDateTime;
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
        if(request.minimumAmount()!=null && request.minimumAmount().signum()<0 || request.maximumAmount()!=null && request.maximumAmount().signum()<0 || request.minimumAmount()!=null && request.maximumAmount()!=null && request.minimumAmount().compareTo(request.maximumAmount())>0)
            throw new DomainException("INVALID_AMOUNT_RANGE", "Amount bounds must be non-negative and minimum must not exceed maximum.");
        int page = request.page() == null ? 0 : request.page(); int size = request.size() == null ? 20 : request.size();
        if (page < 0 || size <= 0) throw new DomainException("INVALID_PAGINATION", "page must be non-negative and size must be positive.");
        TransactionAccount account = transactions.findAccount(request.accountId()).orElseThrow(() -> new DomainException("NOT_FOUND", "Account was not found."));
        if (account.customerId() != request.customerId()) throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
        long offset;
        try { offset = Math.multiplyExact((long) page, size); } catch (ArithmeticException exception) { throw new DomainException("INVALID_PAGINATION", "page is too large."); }
        LocalDateTime start=request.startDate().atStartOfDay(); LocalDateTime endExclusive=request.endDate().plusDays(1).atStartOfDay();
        TransactionPage result=transactions.search(new TransactionQuery(account.accountId(),start,endExclusive,request.transactionType(),request.minimumAmount(),request.maximumAmount(),blankToNull(request.keyword()),request.sortOrder()==null?TransactionSortOrder.RECENT:request.sortOrder(),offset,size));
        var content=result.records().stream().map(item -> new TransactionSearchResponse.TransactionView(item.transactionId(), item.occurredAt(), item.direction(), item.amount(), item.counterparty(), item.memo())).toList();
        int pages = result.totalElements()==0 ? 0 : (int) ((result.totalElements() + (long) size - 1) / size);
        return new TransactionSearchResponse(account.accountId(), account.accountNoMasked(), account.accountName(), MOCK_TIME_ZONE.getId(), content, page, size, result.totalElements(), pages);
    }
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
}
