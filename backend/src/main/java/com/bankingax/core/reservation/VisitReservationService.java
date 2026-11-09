package com.bankingax.core.reservation;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.notification.NotificationWriter;
import com.bankingax.core.persistence.ConditionalOnDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking rules. This lives apart from the controller so @Transactional really wraps the Case lock, the insert, the
 * audit event and the notification: a failed booking leaves no partial state.
 */
@Service
@ConditionalOnDataSource
public class VisitReservationService {
    /** Spec 3-3: change/cancel allowed only until 24 hours before the visit. */
    static final Duration CHANGE_LOCK = Duration.ofHours(24);
    private static final int REQUEST_ID_LIMIT = 64;
    private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ReservationSlotPolicy.ZONE);
    private static final String STAFF_SLOT_KEY = "uk_reservation_active_staff_slot";
    private static final String COLUMNS = "select id,case_id,branch_id,assigned_staff_id,starts_at,status from visit_reservation ";

    private final JdbcTemplate jdbc;
    private final StaffRankingPort ranking;
    private final NotificationWriter notifications;

    public VisitReservationService(JdbcTemplate jdbc, StaffRankingPort ranking, ObjectProvider<NotificationWriter> notifications) {
        this.jdbc = jdbc;
        this.ranking = ranking;
        this.notifications = notifications.getIfAvailable();
    }

    @Transactional
    public VisitReservationController.Item create(long customer, VisitReservationController.Create q) {
        if (q.requestId() == null || q.requestId().isBlank() || q.requestId().length() > REQUEST_ID_LIMIT)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
        lockBranch(q.branchId());
        String assignRequest = q.requestId() + "-assign";
        Optional<VisitReservationController.Item> replay = jdbc.query(COLUMNS + "where customer_id=? and id=(select cast(entity_id as unsigned) from event_log where request_id=?)",
                (r, n) -> row(r), customer, assignRequest).stream().findFirst();
        if (replay.isPresent()) return replay.get();

        var cases = jdbc.query("select case_type,case_status,result_notified_at from service_case where id=? and customer_id=? for update",
                (r, n) -> new CaseRow(r.getString(1), r.getString(2), r.getTimestamp(3)), q.caseId(), customer);
        if (cases.isEmpty() || !"REVIEW_PASSED".equals(cases.get(0).status()) || cases.get(0).notified() == null)
            throw new DomainException("RESERVATION_NOT_ALLOWED", "Staff PROCEED and customer notification are required.");
        if (q.startsAt() == null || !ReservationSlotPolicy.bookable(q.startsAt().toInstant(), Instant.now()))
            throw new DomainException("RESERVATION_SLOT_UNAVAILABLE", "This visit time cannot be booked.");
        String branchName = activeBranchName(q.branchId());
        Integer active = jdbc.queryForObject("select count(*) from visit_reservation where case_id=? and status not in ('CANCELED','EXPIRED')", Integer.class, q.caseId());
        if (active != null && active > 0) throw new DomainException("RESERVATION_ALREADY_EXISTS", "This Case already has a reservation.");

        Timestamp at = Timestamp.from(q.startsAt().toInstant());
        // Spring filters candidates and counts their workload; the ranking port only orders these candidates.
        List<StaffRankingPort.Candidate> candidates = freeStaff(q.branchId(), at);
        List<Long> ids = candidates.stream().map(StaffRankingPort.Candidate::staffId).toList();
        // No free staff at this time means no capacity: nothing is booked and no seat is invented.
        if (ids.isEmpty()) throw new DomainException("RESERVATION_SLOT_UNAVAILABLE", "No branch staff member is free at this time.");
        List<Long> ranked;
        String rankingFailure = null;
        try { ranked = q.staffId() == null ? ranking.rank(candidates, cases.get(0).type()) : List.of(); }
        catch (RuntimeException failure) { ranked = List.of(); rankingFailure = "RANKING_FAILED"; }
        if (q.staffId() != null && !ids.contains(q.staffId())) throw new DomainException("FORBIDDEN", "Staff is not eligible for the branch.");
        List<Long> order = q.staffId() != null ? List.of(q.staffId()) : tryOrder(ranked, ids);
        // Staff are free but the ranking named none of them: the request waits for a branch staff member to assign it.
        if (order.isEmpty()) return awaitManualAssignment(customer, q, at, branchName, assignRequest, candidates.size(), rankingFailure == null ? "RANKING_EMPTY" : rankingFailure);
        Long staff = book(q, customer, at, order);
        VisitReservationController.Item item = jdbc.queryForObject(COLUMNS + "where case_id=? and status not in ('CANCELED','EXPIRED')", (r, n) -> row(r), q.caseId());
        Long chosen = staff;
        int load = candidates.stream().filter(candidate -> candidate.staffId() == chosen).mapToInt(StaffRankingPort.Candidate::activeAssignments).findFirst().orElse(0);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_ASSIGNED',null,?,'SYSTEM',null,?,JSON_OBJECT('caseId',?,'basis','TASK_CODE_AND_WORKLOAD','candidateCount',?,'assignedWorkload',?,'recommended',?,'branchId',?,'staffChosenByCustomer',?,'ranked',cast(? as json)))",
                String.valueOf(item.id()), String.valueOf(chosen), assignRequest, q.caseId(), candidates.size(), load, ranked.isEmpty() ? null : String.valueOf(ranked.get(0)),
                q.branchId(), q.staffId() != null, rankedJson(ranked, candidates));
        if (notifications != null) notifications.store(customer, q.caseId(), null, "VISIT_CONFIRMED", "방문 예약이 확정되었어요",
                "예약번호 %s · %s · %s. 방문 24시간 전까지 변경·취소할 수 있어요. 예약은 예금 지급 확정이 아니에요."
                        .formatted(number(item.id()), branchName, NOTICE_TIME.format(item.startsAt())), "visit-confirmed-" + item.id());
        return item;
    }

    @Transactional
    public void cancel(long customer, long id, String requestId) {
        Timestamp lockAt = Timestamp.from(Instant.now().plus(CHANGE_LOCK));
        var rows = jdbc.query("select case_id,status,starts_at from visit_reservation where id=? and customer_id=? for update",
                (r, n) -> new CancelRow(r.getLong(1), r.getString(2), r.getTimestamp(3)), id, customer);
        if (rows.isEmpty() || "CANCELED".equals(rows.get(0).status()) || "EXPIRED".equals(rows.get(0).status())) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        if (!rows.get(0).startsAt().after(lockAt))
            throw new DomainException("RESERVATION_CHANGE_LOCKED", "Reservations can only be changed or canceled until 24 hours before the visit.");
        jdbc.update("update visit_reservation set status='CANCELED',version=version+1 where id=?", id);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_CANCELED',?,'CANCELED','CUSTOMER',?,?,JSON_OBJECT('caseId',?))",
                String.valueOf(id), rows.get(0).status(), customer, "visit-cancel-" + id, rows.get(0).caseId());
        if (notifications != null) notifications.store(customer, rows.get(0).caseId(), null, "VISIT_CANCELED", "방문 예약이 취소되었어요",
                "예약번호 %s가 취소되었어요. 다시 예약할 수 있어요.".formatted(number(id)), "visit-canceled-" + id);
    }

    /**
     * One booking per branch at a time. Requests that try the same staff in the same order otherwise deadlock on the
     * unique key and get turned away while staff are still free. This must be the transaction's first read, so the
     * replay check and the free-staff count below see everything committed while this request waited.
     */
    private void lockBranch(long branchId) {
        jdbc.queryForList("select id from branch where id=? for update", Long.class, branchId);
    }

    private VisitReservationController.Item awaitManualAssignment(long customer, VisitReservationController.Create q, Timestamp at, String branchName,
                                                                   String assignRequest, int candidateCount, String reason) {
        jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(?,?,?,null,?,'NEEDS_MANUAL_ASSIGNMENT')",
                q.caseId(), customer, q.branchId(), at);
        VisitReservationController.Item item = jdbc.queryForObject(COLUMNS + "where case_id=? and status not in ('CANCELED','EXPIRED')", (r, n) -> row(r), q.caseId());
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_NEEDS_MANUAL_ASSIGNMENT',null,'NEEDS_MANUAL_ASSIGNMENT','SYSTEM',null,?,JSON_OBJECT('caseId',?,'branchId',?,'candidateCount',?,'reason',?))",
                String.valueOf(item.id()), assignRequest, q.caseId(), q.branchId(), candidateCount, reason);
        if (notifications != null) notifications.store(customer, q.caseId(), null, "VISIT_PENDING_ASSIGNMENT", "방문 예약 요청이 접수됐어요",
                "예약번호 %s · %s · %s. 영업점이 담당 직원을 배정하면 확정 안내를 드려요. 배정 전에는 예약 확정이 아니에요."
                        .formatted(number(item.id()), branchName, NOTICE_TIME.format(item.startsAt())), "visit-pending-" + item.id());
        return item;
    }

    /** Free staff of the reservation's branch at its time, for a branch staff member to pick from. */
    @Transactional(readOnly = true)
    public List<StaffRankingPort.Candidate> manualCandidates(long actorStaffId, long reservationId) {
        var row = branchReservation(actorStaffId, reservationId, false);
        return byWorkload(freeStaff(row.branchId(), row.startsAt()));
    }

    /** The order staff are told about: lowest current workload first, the same tie-break as the automatic ranking. */
    private static List<StaffRankingPort.Candidate> byWorkload(List<StaffRankingPort.Candidate> free) {
        return StaffRankingPort.byWorkload(free).stream().map(id -> free.stream().filter(row -> row.staffId() == id).findFirst().orElseThrow()).toList();
    }

    /** VISIT-08: a staff member of the same branch assigns a free colleague; every rule is checked again under the row lock. */
    @Transactional
    public VisitReservationController.Item manualAssign(long actorStaffId, long reservationId, long staffId, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > REQUEST_ID_LIMIT) throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
        List<String> replay = jdbc.queryForList("select entity_id from event_log where request_id=?", String.class, requestId + "-manual");
        if (!replay.isEmpty()) return jdbc.queryForObject(COLUMNS + "where id=?", (r, n) -> row(r), Long.valueOf(replay.get(0)));
        var row = branchReservation(actorStaffId, reservationId, true);
        if (!"NEEDS_MANUAL_ASSIGNMENT".equals(row.status())) throw new DomainException("INVALID_STATE", "Only a reservation waiting for manual assignment can be assigned.");
        if (!row.startsAt().toInstant().isAfter(Instant.now())) throw new DomainException("RESERVATION_SLOT_UNAVAILABLE", "The visit time has passed.");
        String caseStatus = jdbc.queryForObject("select case_status from service_case where id=?", String.class, row.caseId());
        if (!"REVIEW_PASSED".equals(caseStatus)) throw new DomainException("RESERVATION_NOT_ALLOWED", "The Case is no longer open for a visit.");
        List<StaffRankingPort.Candidate> free = byWorkload(freeStaff(row.branchId(), row.startsAt()));
        if (free.stream().noneMatch(candidate -> candidate.staffId() == staffId))
            throw new DomainException("RESERVATION_CONFLICT", "That staff member is not free at this time in this branch.", true);
        int load = free.stream().filter(candidate -> candidate.staffId() == staffId).findFirst().orElseThrow().activeAssignments();
        List<Long> ordered = free.stream().map(StaffRankingPort.Candidate::staffId).toList();
        try {
            jdbc.update("update visit_reservation set status='CONFIRMED', assigned_staff_id=?, version=version+1 where id=? and status='NEEDS_MANUAL_ASSIGNMENT'", staffId, reservationId);
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException("RESERVATION_CONFLICT", "That staff member was just booked at this time.", true);
        }
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_ASSIGNED','NEEDS_MANUAL_ASSIGNMENT',?,'STAFF',?,?,JSON_OBJECT('caseId',?,'basis','MANUAL','branchId',?,'candidateCount',?,'assignedWorkload',?,'recommended',?,'ranked',cast(? as json)))",
                String.valueOf(reservationId), String.valueOf(staffId), actorStaffId, requestId + "-manual", row.caseId(), row.branchId(),
                free.size(), load, String.valueOf(ordered.get(0)), rankedJson(ordered, free));
        VisitReservationController.Item item = jdbc.queryForObject(COLUMNS + "where id=?", (r, n) -> row(r), reservationId);
        if (notifications != null) notifications.store(row.customerId(), row.caseId(), null, "VISIT_CONFIRMED", "방문 예약이 확정되었어요",
                "예약번호 %s · %s · %s. 영업점에서 담당 직원을 배정했어요. 방문 24시간 전까지 변경·취소할 수 있어요. 예약은 예금 지급 확정이 아니에요."
                        .formatted(number(reservationId), activeBranchName(row.branchId()), NOTICE_TIME.format(item.startsAt())), "visit-confirmed-" + reservationId);
        return item;
    }

    /**
     * VISIT-10: the old reservation is released and the new one booked in one transaction, so a failed booking leaves
     * the old reservation exactly as it was.
     */
    @Transactional
    public VisitReservationController.Item change(long customer, long reservationId, VisitReservationController.Create next) {
        lockBranch(next.branchId());
        List<String> replay = jdbc.queryForList("select entity_id from event_log where request_id=?", String.class, next.requestId() + "-assign");
        if (!replay.isEmpty()) return jdbc.queryForObject(COLUMNS + "where id=?", (r, n) -> row(r), Long.valueOf(replay.get(0)));
        Timestamp lockAt = Timestamp.from(Instant.now().plus(CHANGE_LOCK));
        var rows = jdbc.query("select case_id,status,starts_at from visit_reservation where id=? and customer_id=? for update",
                (r, n) -> new CancelRow(r.getLong(1), r.getString(2), r.getTimestamp(3)), reservationId, customer);
        if (rows.isEmpty() || "CANCELED".equals(rows.get(0).status()) || "EXPIRED".equals(rows.get(0).status())) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        if (!rows.get(0).startsAt().after(lockAt))
            throw new DomainException("RESERVATION_CHANGE_LOCKED", "Reservations can only be changed or canceled until 24 hours before the visit.");
        if (next.caseId() != rows.get(0).caseId()) throw new DomainException("FORBIDDEN", "A change keeps the same Case.");
        jdbc.update("update visit_reservation set status='CANCELED',version=version+1 where id=?", reservationId);
        VisitReservationController.Item created = create(customer, next);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_CHANGED',?,?,'CUSTOMER',?,?,JSON_OBJECT('caseId',?,'from',?,'to',?))",
                String.valueOf(reservationId), rows.get(0).status(), String.valueOf(created.id()), customer, "visit-change-" + next.requestId(),
                rows.get(0).caseId(), reservationId, created.id());
        return created;
    }

    /**
     * NOTI-23: a request still without an assigned staff member when its visit time arrives expires and frees the Case.
     * Confirmed reservations are never expired here. Re-running finds nothing left to do.
     */
    @Transactional
    public int expireUnassigned() {
        var due = jdbc.query("select id, case_id, customer_id, status from visit_reservation where status in ('NEEDS_MANUAL_ASSIGNMENT','PENDING_ASSIGNMENT','SELECTED') and starts_at <= UTC_TIMESTAMP(6) for update",
                (r, n) -> new long[] { r.getLong(1), r.getLong(2), r.getLong(3) }, new Object[0]);
        for (long[] row : due) {
            int changed = jdbc.update("update visit_reservation set status='EXPIRED', version=version+1 where id=? and status in ('NEEDS_MANUAL_ASSIGNMENT','PENDING_ASSIGNMENT','SELECTED')", row[0]);
            if (changed == 0) continue;
            jdbc.update("insert ignore into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                    + "values('VISIT_RESERVATION',?,'VISIT_EXPIRED',null,'EXPIRED','SYSTEM',null,?,JSON_OBJECT('caseId',?))", String.valueOf(row[0]), "visit-expired-" + row[0], row[1]);
            if (notifications != null) notifications.store(row[2], row[1], null, "VISIT_EXPIRED", "방문 예약이 만료되었어요",
                    "예약번호 %s는 담당 직원이 배정되기 전에 방문 시간이 지나 만료됐어요. 다시 예약할 수 있어요.".formatted(number(row[0])), "visit-expired-" + row[0]);
        }
        return due.size();
    }

    private record BranchRow(long id, long caseId, long customerId, long branchId, Timestamp startsAt, String status) { }

    private BranchRow branchReservation(long actorStaffId, long reservationId, boolean lock) {
        Long actorBranch = jdbc.query("select branch_id from `user` where id=? and role='STAFF' and is_active=true", rs -> rs.next() ? (Long) rs.getObject(1, Long.class) : null, actorStaffId);
        if (actorBranch == null) throw new DomainException("FORBIDDEN", "This staff account has no branch.");
        var rows = jdbc.query("select id, case_id, customer_id, branch_id, starts_at, status from visit_reservation where id=?" + (lock ? " for update" : ""),
                (r, n) -> new BranchRow(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getTimestamp(5), r.getString(6)), reservationId);
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        if (rows.get(0).branchId() != actorBranch) throw new DomainException("FORBIDDEN", "The reservation belongs to another branch.");
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<VisitReservationController.Item> list(long customer) {
        return jdbc.query(COLUMNS + "where customer_id=? order by starts_at", (r, n) -> row(r), customer);
    }

    /** Offers a time only while a branch staff member is free; booking re-checks it under the Case lock. */
    @Transactional(readOnly = true)
    public List<VisitReservationController.Slot> availability(long branchId, LocalDate date) {
        activeBranchName(branchId);
        Integer capacity = jdbc.queryForObject("select count(*) from `user` where role='STAFF' and branch_id=? and is_active=true", Integer.class, branchId);
        int total = capacity == null ? 0 : capacity;
        return ReservationSlotPolicy.slots(date, Instant.now()).stream().map(at -> {
            int remaining = freeStaff(branchId, Timestamp.from(at)).size();
            return new VisitReservationController.Slot(at, remaining > 0, total, Math.max(0, total - remaining), remaining);
        }).toList();
    }

    static String number(long id) { return "V-%06d".formatted(id); }

    private String activeBranchName(long branchId) {
        return jdbc.query("select name from branch where id=? and is_active=true", (r, n) -> r.getString(1), branchId).stream().findFirst()
                .orElseThrow(() -> new DomainException("RESERVATION_BRANCH_UNAVAILABLE", "This branch cannot be booked."));
    }

    /** [{staffId, rank, activeAssignments}] in the order the ranking returned, for the assignment record. */
    private static String rankedJson(List<Long> ranked, List<StaffRankingPort.Candidate> candidates) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < ranked.size(); index++) {
            long id = ranked.get(index);
            int load = candidates.stream().filter(candidate -> candidate.staffId() == id).mapToInt(StaffRankingPort.Candidate::activeAssignments).findFirst().orElse(0);
            if (index > 0) out.append(',');
            out.append("{\"staffId\":").append(id).append(",\"rank\":").append(index + 1).append(",\"activeAssignments\":").append(load).append('}');
        }
        return out.append(']').toString();
    }

    /** Ranked order, free staff only. With no ranking this is empty, so the booking stays on the manual assignment path. */
    private static List<Long> tryOrder(List<Long> ranked, List<Long> ids) {
        return ranked.stream().filter(ids::contains).distinct().toList();
    }

    /** The unique keys decide the race. Only a taken staff time moves on to the next person; a second booking for the Case does not. */
    private Long book(VisitReservationController.Create q, long customer, Timestamp at, List<Long> order) {
        for (Long staff : order) {
            try {
                jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(?,?,?,?,?,'CONFIRMED')",
                        q.caseId(), customer, q.branchId(), staff, at);
                return staff;
            } catch (DataIntegrityViolationException conflict) {
                if (!String.valueOf(conflict.getMessage()).contains(STAFF_SLOT_KEY)) break;
            } catch (ConcurrencyFailureException conflict) {
                break;
            }
        }
        throw new DomainException("RESERVATION_CONFLICT", "Staff or case already has a reservation.", true);
    }

    private List<StaffRankingPort.Candidate> freeStaff(long branchId, Timestamp at) {
        return jdbc.query("select u.id,(select count(*) from visit_reservation v where v.assigned_staff_id=u.id and v.status not in ('CANCELED','EXPIRED') and v.starts_at>=UTC_TIMESTAMP(6)) "
                + "from `user` u where u.role='STAFF' and u.branch_id=? and u.is_active=true "
                + "and not exists(select 1 from visit_reservation b where b.assigned_staff_id=u.id and b.status not in ('CANCELED','EXPIRED') and b.starts_at=?)",
                (r, n) -> new StaffRankingPort.Candidate(r.getLong(1), r.getInt(2)), branchId, at);
    }

    private static VisitReservationController.Item row(ResultSet r) throws SQLException {
        return new VisitReservationController.Item(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getTimestamp(5).toInstant(), r.getString(6));
    }

    private record CaseRow(String type, String status, Timestamp notified) { }
    private record CancelRow(long caseId, String status, Timestamp startsAt) { }
}
