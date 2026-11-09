package com.bankingax.core.reservation;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.tool.ActorRole;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JWT customer boundary for reservation create/list/cancel. Spring keeps all eligibility checks. */
@RestController
@RequestMapping("/api/v1/customer/reservations")
@com.bankingax.core.persistence.ConditionalOnDataSource
public class CustomerReservationBffController {
    private final VisitReservationController internal;
    private final VisitReservationService service;
    private final TaskBffPrincipal principals;
    private final String gatewayToken;

    public CustomerReservationBffController(VisitReservationController internal, VisitReservationService service, TaskBffPrincipal principals,
                                            @Value("${app.tool-gateway-token:}") String gatewayToken) {
        this.internal = internal;
        this.service = service;
        this.principals = principals;
        this.gatewayToken = gatewayToken;
    }

    @PostMapping
    public ApiEnvelope<VisitReservationController.Item> create(JwtAuthenticationToken authentication,
                                                                 @RequestBody VisitReservationController.Create body) {
        long customerId = principals.customer(authentication);
        return internal.create(customerId, ActorRole.CUSTOMER, gatewayToken, withoutStaffChoice(body));
    }

    @GetMapping
    public ApiEnvelope<List<VisitReservationController.Item>> list(JwtAuthenticationToken authentication,
                                                                     @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.list(customerId, ActorRole.CUSTOMER, gatewayToken, requestId);
    }

    /** VISIT-10: body carries the same caseId and the new branch/time; a failed booking keeps the old reservation. */
    @PostMapping("/{id}/change")
    public ApiEnvelope<VisitReservationController.Item> change(JwtAuthenticationToken authentication, @PathVariable long id,
                                                                 @RequestBody VisitReservationController.Create body) {
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(body.requestId(), "VISIT_RESERVATION", null, body.caseId(), null, service.change(customerId, id, withoutStaffChoice(body)));
    }

    /** Spring assigns by workload; a customer request never names the staff member. */
    private static VisitReservationController.Create withoutStaffChoice(VisitReservationController.Create body) {
        return new VisitReservationController.Create(body.requestId(), body.caseId(), body.branchId(), null, body.startsAt());
    }

    @PostMapping("/{id}/cancel")
    public ApiEnvelope<Void> cancel(JwtAuthenticationToken authentication, @PathVariable long id,
                                    @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        return internal.cancel(id, customerId, ActorRole.CUSTOMER, gatewayToken, requestId);
    }
}
