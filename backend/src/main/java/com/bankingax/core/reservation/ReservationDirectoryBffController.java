package com.bankingax.core.reservation;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only lookups for the reservation screens: active branches for the customer picker and the
 * signed-in staff member's own branch reservations. Creating, confirming and cancelling stay in
 * VisitReservationController.
 */
@RestController
@com.bankingax.core.persistence.ConditionalOnDataSource
public class ReservationDirectoryBffController {
    private final JdbcTemplate jdbc;
    private final TaskBffPrincipal principals;
    private final VisitReservationService reservations;

    public ReservationDirectoryBffController(JdbcTemplate jdbc, TaskBffPrincipal principals, VisitReservationService reservations) {
        this.jdbc = jdbc;
        this.principals = principals;
        this.reservations = reservations;
    }

    @GetMapping("/api/v1/customer/reservations/branches")
    @Transactional(readOnly = true)
    public ApiEnvelope<List<Branch>> branches(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        principals.customer(authentication);
        List<Branch> rows = jdbc.query("select id, name, address from branch where is_active=true order by id",
                (rs, n) -> new Branch(rs.getLong(1), rs.getString(2), rs.getString(3)));
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, rows);
    }

    @GetMapping("/api/v1/customer/reservations/availability")
    public ApiEnvelope<List<VisitReservationController.Slot>> availability(JwtAuthenticationToken authentication, @RequestParam long branchId,
            @RequestParam LocalDate date, @RequestParam String requestId) {
        principals.customer(authentication);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, reservations.availability(branchId, date));
    }

    @GetMapping("/api/v1/staff/reservations")
    @Transactional(readOnly = true)
    public ApiEnvelope<BranchReservations> staffBranch(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        long staffId = principals.staff(authentication);
        Branch branch = jdbc.query("select b.id, b.name, b.address from `user` u join branch b on b.id=u.branch_id where u.id=? and u.is_active=true",
                rs -> rs.next() ? new Branch(rs.getLong(1), rs.getString(2), rs.getString(3)) : null, staffId);
        if (branch == null) throw new DomainException("FORBIDDEN", "This staff account has no branch.");
        List<StaffReservation> rows = jdbc.query("""
            select vr.id, vr.case_id, vr.starts_at, vr.status, vr.assigned_staff_id, c.name, s.name, sc.case_type
              from visit_reservation vr
              join `user` c on c.id = vr.customer_id
              join service_case sc on sc.id = vr.case_id
              left join `user` s on s.id = vr.assigned_staff_id
             where vr.branch_id=? and vr.status<>'CANCELED'
             order by vr.starts_at
            """, (rs, n) -> new StaffReservation(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getString(4),
                rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8)), branch.id());
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, new BranchReservations(branch, rows));
    }

    public record Branch(long id, String name, String address) { }
    public record StaffReservation(long id, long caseId, Instant startsAt, String status, Long assignedStaffId,
                                   String customerName, String assignedStaffName, String caseType) { }
    public record BranchReservations(Branch branch, List<StaffReservation> reservations) { }
}
