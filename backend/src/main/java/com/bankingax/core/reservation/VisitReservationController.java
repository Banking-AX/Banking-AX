package com.bankingax.core.reservation;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer reservation API. Eligibility, owner and active slot constraints stay server authoritative. */
@RestController
@ConditionalOnProperty(name = "app.reservation.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/reservations")
public class VisitReservationController {
    private static final Duration CHANGE_CUTOFF = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final InternalGatewayAuthenticator auth;
    public VisitReservationController(JdbcTemplate jdbc, InternalGatewayAuthenticator auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @PostMapping
    @Transactional
    public ApiEnvelope<Item> create(@RequestHeader("X-Actor-Id") long actor,
                                    @RequestHeader("X-Actor-Role") ActorRole role,
                                    @RequestHeader("X-Tool-Gateway-Token") String token,
                                    @RequestBody Create request) {
        customer(actor, role, token);
        return ApiEnvelope.success(request.requestId(), "VISIT_RESERVATION", null,
                request.caseId(), null, create(actor, request));
    }

    @GetMapping
    public ApiEnvelope<List<Item>> list(@RequestHeader("X-Actor-Id") long actor,
                                        @RequestHeader("X-Actor-Role") ActorRole role,
                                        @RequestHeader("X-Tool-Gateway-Token") String token,
                                        @RequestParam String requestId) {
        customer(actor, role, token);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null,
                jdbc.query("select v.id,v.case_id,c.work_item_id,w.task_code,v.branch_id,v.assigned_staff_id,v.starts_at,v.status,v.version " +
                                "from visit_reservation v join service_case c on c.id=v.case_id " +
                                "join work_item w on w.id=c.work_item_id where v.customer_id=? order by v.starts_at",
                        VisitReservationController::rowWithVersion, actor));
    }

    @GetMapping("/eligible-cases")
    public ApiEnvelope<List<EligibleCase>> eligibleCases(@RequestHeader("X-Actor-Id") long actor,
            @RequestHeader("X-Actor-Role") ActorRole role, @RequestHeader("X-Tool-Gateway-Token") String token,
            @RequestParam String requestId) {
        customer(actor, role, token);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null,
                jdbc.query("select c.id,w.task_code,c.revision from service_case c join work_item w on w.id=c.work_item_id where c.customer_id=? " +
                                "and c.case_type=w.task_code " +
                                "and c.case_status='REVIEW_PASSED' and c.result_notified_at is not null and c.visit_required=true " +
                                "and exists (select 1 from staff_review r where r.case_id=c.id and r.revision=c.revision " +
                                "and r.result='PROCEED' and r.decided_at is not null) " +
                                "and not exists (select 1 from visit_reservation v where v.case_id=c.id and v.status<>'CANCELED') order by c.id desc",
                        (rs, row) -> new EligibleCase(rs.getLong(1), rs.getString(2), rs.getInt(3)), actor));
    }

    @GetMapping("/branches")
    public ApiEnvelope<List<BranchOption>> branches(@RequestHeader("X-Actor-Id") long actor,
            @RequestHeader("X-Actor-Role") ActorRole role, @RequestHeader("X-Tool-Gateway-Token") String token,
            @RequestParam String requestId) {
        customer(actor, role, token);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null,
                jdbc.query("select id,branch_code,name from branch where is_active=true order by name,id",
                        (rs, row) -> new BranchOption(rs.getLong(1), rs.getString(2), rs.getString(3))));
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ApiEnvelope<Void> cancel(@PathVariable long id,
                                    @RequestHeader("X-Actor-Id") long actor,
                                    @RequestHeader("X-Actor-Role") ActorRole role,
                                    @RequestHeader("X-Tool-Gateway-Token") String token,
                                    @RequestParam String requestId) {
        customer(actor, role, token);
        cancelReservation(actor, id, requestId);
        return ApiEnvelope.success(requestId, "VISIT_RESERVATION", null, null, null, null);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiEnvelope<Item> reschedule(@PathVariable long id,
                                        @RequestHeader("X-Actor-Id") long actor,
                                        @RequestHeader("X-Actor-Role") ActorRole role,
                                        @RequestHeader("X-Tool-Gateway-Token") String token,
                                        @RequestBody Reschedule request) {
        customer(actor, role, token);
        Item changed = rescheduleReservation(actor, id, request);
        return ApiEnvelope.success(request.requestId(), "VISIT_RESERVATION", null,
                changed.caseId(), null, changed);
    }

    @Transactional
    Item create(long customerId, Create request) {
        validateRequestId(request.requestId());
        if (request.startsAt() == null) throw new DomainException("INVALID_RESERVATION_TIME", "A visit time is required.");
        Instant startsAt = normalize(request.startsAt());
        requireFuture(startsAt);
        EventReplay replay = findEvent(request.requestId(), customerId);
        if (replay != null) {
            if (!"VISIT_RESERVATION_CREATED".equals(replay.eventType())) throw duplicateRequest();
            Item existing = findReservation(Long.parseLong(replay.entityId()), customerId);
            if (existing.caseId() != request.caseId() || existing.branchId() != request.branchId()
                    || !existing.startsAt().equals(startsAt)) throw duplicateRequest();
            return existing;
        }

        lockEligibleCase(customerId, request.caseId());
        lockActiveBranch(request.branchId());
        ensureSlotAvailable(request.branchId(), startsAt, null);
        try {
            jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) " +
                            "values(?,?,?,NULL,?,'PENDING_ASSIGNMENT')",
                    request.caseId(), customerId, request.branchId(), Timestamp.from(startsAt));
            Item created = jdbc.queryForObject("select v.id,v.case_id,c.work_item_id,w.task_code,v.branch_id,v.assigned_staff_id,v.starts_at,v.status,v.version " +
                            "from visit_reservation v join service_case c on c.id=v.case_id " +
                            "join work_item w on w.id=c.work_item_id where v.case_id=? and v.customer_id=? and v.status<>'CANCELED'",
                    VisitReservationController::rowWithVersion, request.caseId(), customerId);
            recordEvent(created, "VISIT_RESERVATION_CREATED", null, created.startsAt(), customerId, request.requestId());
            return created;
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException("RESERVATION_CONFLICT", "The Case or employee already has an active reservation.", true);
        }
    }

    @Transactional
    void cancelReservation(long customerId, long id, String requestId) {
        validateRequestId(requestId);
        EventReplay replay = findEvent(requestId, customerId);
        if (replay != null) {
            if ("VISIT_RESERVATION_CANCELED".equals(replay.eventType()) && replay.entityId().equals(Long.toString(id))) return;
            throw duplicateRequest();
        }

        long branchId = reservationBranchId(customerId, id);
        lockBranchForUpdate(branchId);
        Item current = lockOwnedReservation(customerId, id);
        enforceChangeWindow(current.startsAt());
        int updated = jdbc.update("update visit_reservation set status='CANCELED',version=version+1 " +
                        "where id=? and customer_id=? and version=? " +
                        "and status in ('SELECTED','PENDING_ASSIGNMENT','CONFIRMED','NEEDS_MANUAL_ASSIGNMENT')",
                id, customerId, current.version());
        if (updated != 1) throw new DomainException("RESERVATION_STATE_CHANGED", "The reservation changed; refresh and try again.", true);
        recordEvent(current, "VISIT_RESERVATION_CANCELED", current.startsAt(), null, customerId, requestId);
    }

    @Transactional
    Item rescheduleReservation(long customerId, long id, Reschedule request) {
        validateRequestId(request.requestId());
        if (request.startsAt() == null) throw new DomainException("INVALID_RESERVATION_TIME", "A visit time is required.");
        Instant startsAt = normalize(request.startsAt());
        requireFuture(startsAt);
        EventReplay replay = findEvent(request.requestId(), customerId);
        if (replay != null) {
            if ("VISIT_RESERVATION_RESCHEDULED".equals(replay.eventType()) && replay.entityId().equals(Long.toString(id))) {
                Item existing = findReservation(id, customerId);
                if (existing.branchId() == request.branchId() && existing.startsAt().equals(startsAt)) return existing;
            }
            throw duplicateRequest();
        }

        long caseId = ownedReservationCaseId(customerId, id);
        lockEligibleCase(customerId, caseId);
        long oldBranchId = reservationBranchId(customerId, id);
        lockBranchesForUpdate(oldBranchId, request.branchId());
        lockActiveBranch(request.branchId());
        Item current = lockOwnedReservation(customerId, id);
        enforceChangeWindow(current.startsAt());
        ensureSlotAvailable(request.branchId(), startsAt, id);
        try {
            int updated = jdbc.update("update visit_reservation set branch_id=?,assigned_staff_id=NULL,starts_at=?," +
                            "status='PENDING_ASSIGNMENT',version=version+1 " +
                            "where id=? and customer_id=? and version=? and status in ('PENDING_ASSIGNMENT','CONFIRMED','NEEDS_MANUAL_ASSIGNMENT')",
                    request.branchId(), Timestamp.from(startsAt), id, customerId, current.version());
            if (updated != 1) throw new DomainException("RESERVATION_STATE_CHANGED", "The reservation changed; refresh and try again.", true);
            Item changed = findReservation(id, customerId);
            recordEvent(changed, "VISIT_RESERVATION_RESCHEDULED", current.startsAt(), changed.startsAt(), customerId, request.requestId());
            return changed;
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException("RESERVATION_CONFLICT", "The Case or employee already has an active reservation.", true);
        }
    }

    static boolean mayChangeBeforeCutoff(Instant now, Instant visitAt) {
        return !now.isAfter(visitAt.minus(CHANGE_CUTOFF));
    }

    private void enforceChangeWindow(Instant visitAt) {
        if (!mayChangeBeforeCutoff(Instant.now(), visitAt))
            throw new DomainException("RESERVATION_CHANGE_CUTOFF", "Reservations can only be changed or canceled at least 24 hours before the visit.");
    }

    private CaseDetails lockEligibleCase(long customerId, long caseId) {
        List<CaseDetails> cases = jdbc.query("select c.case_status,c.result_notified_at,c.visit_required, " +
                        "exists(select 1 from staff_review r where r.case_id=c.id and r.revision=c.revision " +
                        "and r.result='PROCEED' and r.decided_at is not null) " +
                        "and exists(select 1 from work_item w where w.id=c.work_item_id and w.task_code=c.case_type " +
                        "and w.task_code in ('DEBT_ADJUSTMENT','INHERITANCE_DEPOSIT')) " +
                        "from service_case c where c.id=? and c.customer_id=? for update",
                (rs, row) -> new CaseDetails(rs.getString(1), rs.getTimestamp(2), rs.getBoolean(3), rs.getBoolean(4)),
                caseId, customerId);
        if (cases.isEmpty() || !"REVIEW_PASSED".equals(cases.get(0).status())
                || cases.get(0).notifiedAt() == null || !cases.get(0).visitRequired() || !cases.get(0).proceedRecorded())
            throw new DomainException("RESERVATION_NOT_ALLOWED", "A notified, visit-required Case with staff PROCEED is required.");
        return cases.get(0);
    }

    private void lockActiveBranch(long branchId) {
        List<Long> branches = jdbc.query("select id from branch where id=? and is_active=true for update",
                (rs, row) -> rs.getLong(1), branchId);
        if (branches.isEmpty()) throw new DomainException("BRANCH_NOT_AVAILABLE", "The selected branch is unavailable.");
    }

    private void lockBranchForUpdate(long branchId) {
        List<Long> branches = jdbc.query("select id from branch where id=? for update", (rs, row) -> rs.getLong(1), branchId);
        if (branches.isEmpty()) throw new DomainException("BRANCH_NOT_AVAILABLE", "The reservation branch is unavailable.");
    }

    private void lockBranchesForUpdate(long first, long second) {
        long low = Math.min(first, second), high = Math.max(first, second);
        lockBranchForUpdate(low);
        if (high != low) lockBranchForUpdate(high);
    }

    private void ensureSlotAvailable(long branchId, Instant startsAt, Long excludedReservationId) {
        String sql = "select id from visit_reservation where branch_id=? and starts_at=? and status<>'CANCELED'" +
                (excludedReservationId == null ? "" : " and id<>?") + " for update";
        List<Long> conflicts = excludedReservationId == null
                ? jdbc.query(sql, (rs, row) -> rs.getLong(1), branchId, Timestamp.from(startsAt))
                : jdbc.query(sql, (rs, row) -> rs.getLong(1), branchId, Timestamp.from(startsAt), excludedReservationId);
        if (!conflicts.isEmpty()) throw new DomainException("RESERVATION_SLOT_UNAVAILABLE", "This branch time is already reserved.", true);
    }

    private long reservationBranchId(long customerId, long id) {
        List<Long> branches = jdbc.query("select branch_id from visit_reservation where id=? and customer_id=?",
                (rs, row) -> rs.getLong(1), id, customerId);
        if (branches.isEmpty()) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        return branches.get(0);
    }

    private static Instant normalize(OffsetDateTime value) {
        return value.toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireFuture(Instant startsAt) {
        if (!startsAt.isAfter(Instant.now())) throw new DomainException("INVALID_RESERVATION_TIME", "A future visit time is required.");
    }

    private Item lockOwnedReservation(long customerId, long id) {
        List<Item> reservations = jdbc.query("select v.id,v.case_id,c.work_item_id,w.task_code,v.branch_id,v.assigned_staff_id,v.starts_at,v.status,v.version " +
                        "from visit_reservation v join service_case c on c.id=v.case_id " +
                        "join work_item w on w.id=c.work_item_id where v.id=? and v.customer_id=? for update",
                VisitReservationController::rowWithVersion, id, customerId);
        if (reservations.isEmpty()) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        return reservations.get(0);
    }

    private long ownedReservationCaseId(long customerId, long id) {
        List<Long> caseIds = jdbc.query("select case_id from visit_reservation where id=? and customer_id=?",
                (rs, row) -> rs.getLong(1), id, customerId);
        if (caseIds.isEmpty()) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        return caseIds.get(0);
    }

    private Item findReservation(long id, long customerId) {
        List<Item> reservations = jdbc.query("select v.id,v.case_id,c.work_item_id,w.task_code,v.branch_id,v.assigned_staff_id,v.starts_at,v.status,v.version " +
                        "from visit_reservation v join service_case c on c.id=v.case_id " +
                        "join work_item w on w.id=c.work_item_id where v.id=? and v.customer_id=?",
                VisitReservationController::rowWithVersion, id, customerId);
        if (reservations.isEmpty()) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        return reservations.get(0);
    }

    private EventReplay findEvent(String requestId, long actorId) {
        List<EventReplay> events = jdbc.query("select entity_type,entity_id,event_type,actor_id from event_log where request_id=?",
                (rs, row) -> new EventReplay(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), requestId);
        if (events.isEmpty()) return null;
        EventReplay event = events.get(0);
        if (!"VISIT_RESERVATION".equals(event.entityType())) throw duplicateRequest();
        if (event.actorId() != actorId) throw new DomainException("FORBIDDEN", "Reservation operation belongs to another customer.");
        return event;
    }

    private void recordEvent(Item reservation, String eventType, Instant from, Instant to, long actorId, String requestId) {
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) " +
                        "values('VISIT_RESERVATION',?,?,?,?, 'CUSTOMER',?,?,JSON_OBJECT('caseId',?,'branchId',?,'staffId',?))",
                Long.toString(reservation.id()), eventType, from == null ? null : from.toString(),
                to == null ? null : to.toString(), actorId, requestId, reservation.caseId(), reservation.branchId(), reservation.staffId());
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 90)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of 1 to 90 characters is required.");
    }

    private static DomainException duplicateRequest() {
        return new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was already used for another reservation operation.");
    }

    private void customer(long actor, ActorRole role, String token) {
        auth.verify(token);
        if (role != ActorRole.CUSTOMER) throw new DomainException("FORBIDDEN", "Customer access is required.");
    }

    private static Item rowWithVersion(ResultSet rs, int row) throws SQLException {
        return new Item(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                nullableLong(rs, 6), rs.getTimestamp(7).toInstant(), rs.getString(8), rs.getInt(9));
    }

    private static Long nullableLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private record CaseDetails(String status, Timestamp notifiedAt, boolean visitRequired, boolean proceedRecorded) { }
    private record EventReplay(String entityType, String entityId, String eventType, long actorId) { }
    public record Create(String requestId, long caseId, long branchId, OffsetDateTime startsAt) { }
    public record Reschedule(String requestId, long branchId, OffsetDateTime startsAt) { }
    public record EligibleCase(long caseId, String taskCode, int revision) { }
    public record BranchOption(long branchId, String branchCode, String name) { }
    public record Item(long id, long caseId, long workItemId, String taskCode, long branchId, Long staffId,
                       Instant startsAt, String status, int version) { }
}
