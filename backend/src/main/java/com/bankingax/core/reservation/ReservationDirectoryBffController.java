package com.bankingax.core.reservation;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper json;

    public ReservationDirectoryBffController(JdbcTemplate jdbc, TaskBffPrincipal principals, VisitReservationService reservations, ObjectMapper json) {
        this.json = json;
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
                rs.getObject(5) == null ? null : rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), null, List.of()), branch.id());
        List<StaffReservation> enriched = rows.stream().map(row -> new StaffReservation(row.id(), row.caseId(), row.startsAt(), row.status(), row.assignedStaffId(),
                row.customerName(), row.assignedStaffName(), row.caseType(), assignment(row.id()), events(row.id()))).toList();
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, new BranchReservations(branch, enriched));
    }

    @GetMapping("/api/v1/staff/reservations/{id}/candidates")
    @Transactional(readOnly = true)
    public ApiEnvelope<List<RankedCandidate>> candidates(JwtAuthenticationToken authentication, @org.springframework.web.bind.annotation.PathVariable long id, @RequestParam String requestId) {
        long staffId = principals.staff(authentication);
        Map<Long, String> names = new HashMap<>();
        jdbc.query("select id, name from `user` where role='STAFF'", rs -> { names.put(rs.getLong(1), rs.getString(2)); });
        List<StaffRankingPort.Candidate> free = reservations.manualCandidates(staffId, id);
        List<RankedCandidate> out = new ArrayList<>();
        for (int index = 0; index < free.size(); index++) out.add(new RankedCandidate(free.get(index).staffId(), names.get(free.get(index).staffId()), index + 1, free.get(index).activeAssignments()));
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, out);
    }

    @org.springframework.web.bind.annotation.PostMapping("/api/v1/staff/reservations/{id}/assign")
    public ApiEnvelope<VisitReservationController.Item> assign(JwtAuthenticationToken authentication, @org.springframework.web.bind.annotation.PathVariable long id,
                                                                 @org.springframework.web.bind.annotation.RequestBody Assign body) {
        long staffId = principals.staff(authentication);
        return ApiEnvelope.success(body.requestId(), "VISIT_RESERVATION", null, null, null, reservations.manualAssign(staffId, id, body.staffId(), body.requestId()));
    }

    public record Assign(long staffId, String requestId) { }

    /** What the booking recorded. Bookings made before the ranked list was stored have ranked=null, never a reconstructed list. */
    private Assignment assignment(long reservationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select to_value, created_at, cast(payload as char) as payload from event_log where entity_type='VISIT_RESERVATION' and entity_id=? and event_type='VISIT_ASSIGNED' order by id desc limit 1",
                String.valueOf(reservationId));
        if (rows.isEmpty()) return null;
        JsonNode payload;
        try { payload = json.readTree(String.valueOf(rows.get(0).get("payload"))); } catch (Exception exception) { payload = json.createObjectNode(); }
        Map<Long, String> names = new HashMap<>();
        jdbc.query("select id, name from `user` where role='STAFF'", rs -> { names.put(rs.getLong(1), rs.getString(2)); });
        Long assigned = parseLong(rows.get(0).get("to_value"));
        Long recommended = payload.hasNonNull("recommended") ? parseLong(payload.get("recommended").asText()) : null;
        List<RankedCandidate> ranked = null;
        if (payload.has("ranked") && payload.get("ranked").isArray()) {
            ranked = new ArrayList<>();
            for (JsonNode node : payload.get("ranked")) {
                long id = node.path("staffId").asLong();
                ranked.add(new RankedCandidate(id, names.get(id), node.path("rank").asInt(), node.path("activeAssignments").asInt()));
            }
        }
        Object at = rows.get(0).get("created_at");
        return new Assignment(at instanceof java.time.LocalDateTime local ? local.atZone(java.time.ZoneOffset.UTC).toInstant() : at instanceof java.sql.Timestamp ts ? ts.toInstant() : null,
                payload.path("basis").asText(null), payload.path("candidateCount").asInt(0), assigned, assigned == null ? null : names.get(assigned),
                recommended, recommended == null ? null : names.get(recommended), payload.path("assignedWorkload").asInt(0),
                payload.path("staffChosenByCustomer").asBoolean(false), ranked);
    }

    private List<ReservationEvent> events(long reservationId) {
        return jdbc.query("select event_type, from_value, to_value, actor_type, created_at from event_log where entity_type='VISIT_RESERVATION' and entity_id=? order by id",
                (rs, n) -> new ReservationEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), String.valueOf(reservationId));
    }

    private static Long parseLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException exception) { return null; }
    }

    public record RankedCandidate(long staffId, String name, int rank, int activeAssignments) { }
    /** basis TASK_CODE_AND_WORKLOAD: Spring filters by branch and time; the ranking orders by current workload. Not an LLM. */
    public record Assignment(Instant assignedAt, String basis, int candidateCount, Long assignedStaffId, String assignedStaffName,
                             Long recommendedStaffId, String recommendedStaffName, int assignedWorkload, boolean staffChosenByCustomer,
                             List<RankedCandidate> ranked) { }
    public record ReservationEvent(String eventType, String fromValue, String toValue, String actorType, Instant at) { }

    public record Branch(long id, String name, String address) { }
    public record StaffReservation(long id, long caseId, Instant startsAt, String status, Long assignedStaffId,
                                   String customerName, String assignedStaffName, String caseType, Assignment assignment, List<ReservationEvent> events) { }
    public record BranchReservations(Branch branch, List<StaffReservation> reservations) { }
}
