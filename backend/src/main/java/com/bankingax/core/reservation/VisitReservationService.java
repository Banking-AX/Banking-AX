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
        Integer active = jdbc.queryForObject("select count(*) from visit_reservation where case_id=? and status<>'CANCELED'", Integer.class, q.caseId());
        if (active != null && active > 0) throw new DomainException("RESERVATION_ALREADY_EXISTS", "This Case already has a reservation.");

        Timestamp at = Timestamp.from(q.startsAt().toInstant());
        // Spring filters candidates and counts their workload; the ranking port only orders these candidates.
        List<StaffRankingPort.Candidate> candidates = freeStaff(q.branchId(), at);
        List<Long> ids = candidates.stream().map(StaffRankingPort.Candidate::staffId).toList();
        List<Long> ranked = q.staffId() == null && !ids.isEmpty() ? ranking.rank(candidates, cases.get(0).type()) : List.of();
        if (q.staffId() != null && !ids.contains(q.staffId())) throw new DomainException("FORBIDDEN", "Staff is not eligible for the branch.");
        List<Long> order = q.staffId() != null ? List.of(q.staffId()) : tryOrder(ranked, ids);
        if (order.isEmpty()) throw new DomainException("NEEDS_MANUAL_ASSIGNMENT", "No eligible staff candidate.");
        Long staff = book(q, customer, at, order);
        VisitReservationController.Item item = jdbc.queryForObject(COLUMNS + "where case_id=? and status<>'CANCELED'", (r, n) -> row(r), q.caseId());
        Long chosen = staff;
        int load = candidates.stream().filter(candidate -> candidate.staffId() == chosen).mapToInt(StaffRankingPort.Candidate::activeAssignments).findFirst().orElse(0);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_ASSIGNED',null,?,'SYSTEM',null,?,JSON_OBJECT('caseId',?,'basis','TASK_CODE_AND_WORKLOAD','candidateCount',?,'assignedWorkload',?,'recommended',?))",
                String.valueOf(item.id()), String.valueOf(chosen), assignRequest, q.caseId(), candidates.size(), load, ranked.isEmpty() ? null : String.valueOf(ranked.get(0)));
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
        if (rows.isEmpty() || "CANCELED".equals(rows.get(0).status())) throw new DomainException("NOT_FOUND", "Reservation unavailable.");
        if (!rows.get(0).startsAt().after(lockAt))
            throw new DomainException("RESERVATION_CHANGE_LOCKED", "Reservations can only be changed or canceled until 24 hours before the visit.");
        jdbc.update("update visit_reservation set status='CANCELED',version=version+1 where id=?", id);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) "
                + "values('VISIT_RESERVATION',?,'VISIT_CANCELED',?,'CANCELED','CUSTOMER',?,?,JSON_OBJECT('caseId',?))",
                String.valueOf(id), rows.get(0).status(), customer, "visit-cancel-" + id, rows.get(0).caseId());
        if (notifications != null) notifications.store(customer, rows.get(0).caseId(), null, "VISIT_CANCELED", "방문 예약이 취소되었어요",
                "예약번호 %s가 취소되었어요. 다시 예약할 수 있어요.".formatted(number(id)), "visit-canceled-" + id);
    }

    @Transactional(readOnly = true)
    public List<VisitReservationController.Item> list(long customer) {
        return jdbc.query(COLUMNS + "where customer_id=? order by starts_at", (r, n) -> row(r), customer);
    }

    /** Offers a time only while a branch staff member is free; booking re-checks it under the Case lock. */
    @Transactional(readOnly = true)
    public List<VisitReservationController.Slot> availability(long branchId, LocalDate date) {
        activeBranchName(branchId);
        return ReservationSlotPolicy.slots(date, Instant.now()).stream()
                .map(at -> new VisitReservationController.Slot(at, !freeStaff(branchId, Timestamp.from(at)).isEmpty())).toList();
    }

    static String number(long id) { return "V-%06d".formatted(id); }

    private String activeBranchName(long branchId) {
        return jdbc.query("select name from branch where id=? and is_active=true", (r, n) -> r.getString(1), branchId).stream().findFirst()
                .orElseThrow(() -> new DomainException("RESERVATION_BRANCH_UNAVAILABLE", "This branch cannot be booked."));
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
        return jdbc.query("select u.id,(select count(*) from visit_reservation v where v.assigned_staff_id=u.id and v.status<>'CANCELED' and v.starts_at>=UTC_TIMESTAMP(6)) "
                + "from `user` u where u.role='STAFF' and u.branch_id=? and u.is_active=true "
                + "and not exists(select 1 from visit_reservation b where b.assigned_staff_id=u.id and b.status<>'CANCELED' and b.starts_at=?)",
                (r, n) -> new StaffRankingPort.Candidate(r.getLong(1), r.getInt(2)), branchId, at);
    }

    private static VisitReservationController.Item row(ResultSet r) throws SQLException {
        return new VisitReservationController.Item(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getTimestamp(5).toInstant(), r.getString(6));
    }

    private record CaseRow(String type, String status, Timestamp notified) { }
    private record CancelRow(long caseId, String status, Timestamp startsAt) { }
}
