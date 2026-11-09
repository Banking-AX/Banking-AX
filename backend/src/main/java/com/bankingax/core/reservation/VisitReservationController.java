package com.bankingax.core.reservation;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal customer boundary; the booking rules and their transaction are in VisitReservationService. */
@RestController
@RequestMapping("/api/v1/reservations")
@com.bankingax.core.persistence.ConditionalOnDataSource
public class VisitReservationController {
    private final VisitReservationService reservations;
    private final InternalGatewayAuthenticator auth;

    public VisitReservationController(VisitReservationService reservations, InternalGatewayAuthenticator auth) {
        this.reservations = reservations;
        this.auth = auth;
    }

    @PostMapping
    public ApiEnvelope<Item> create(@RequestHeader("X-Actor-Id") long actor, @RequestHeader("X-Actor-Role") ActorRole role,
                                    @RequestHeader("X-Tool-Gateway-Token") String token, @RequestBody Create q) {
        customer(role, token);
        return ApiEnvelope.success(q.requestId(), "VISIT_RESERVATION", null, q.caseId(), null, reservations.create(actor, q));
    }

    @GetMapping
    public ApiEnvelope<List<Item>> list(@RequestHeader("X-Actor-Id") long actor, @RequestHeader("X-Actor-Role") ActorRole role,
                                        @RequestHeader("X-Tool-Gateway-Token") String token, @RequestParam String requestId) {
        customer(role, token);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, reservations.list(actor));
    }

    @PostMapping("/{id}/cancel")
    public ApiEnvelope<Void> cancel(@PathVariable long id, @RequestHeader("X-Actor-Id") long actor, @RequestHeader("X-Actor-Role") ActorRole role,
                                    @RequestHeader("X-Tool-Gateway-Token") String token, @RequestParam String requestId) {
        customer(role, token);
        reservations.cancel(actor, id, requestId);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, null);
    }

    private void customer(ActorRole role, String token) {
        auth.verify(token);
        if (role != ActorRole.CUSTOMER) throw new DomainException("FORBIDDEN", "Customer access is required.");
    }

    public record Create(String requestId, long caseId, long branchId, Long staffId, OffsetDateTime startsAt) { }
    public record Item(long id, long caseId, long branchId, long staffId, Instant startsAt, String status) { }
    /** capacity = active staff of the branch; booked = those already holding this time; remaining = free staff now. Re-checked when booking. */
    public record Slot(Instant startsAt, boolean available, int capacity, int booked, int remaining) { }
}
