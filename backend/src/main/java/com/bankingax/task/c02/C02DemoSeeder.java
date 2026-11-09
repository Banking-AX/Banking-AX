package com.bankingax.task.c02;

import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dev-only demo data for the C-02 staff/reservation demo while the SOLAPI module is pending.
 * Each scenario runs the real path: workflow start → C-02 prepare → document upload → consent →
 * {@link C02IntakeService#submitForDemoSeed} (same post-submit path as a real submission, minus SMS).
 * Once SMS verification exists the same Cases can be produced by the customer UI, so nothing here
 * is needed in production: it never loads in the production profile and is off unless
 * app.demo-seed.c02-enabled=true. Every scenario is idempotent via an event_log marker.
 */
@Component
@Profile("!production")
@com.bankingax.core.persistence.ConditionalOnDataSource
@ConditionalOnProperty(name = "app.demo-seed.c02-enabled", havingValue = "true")
public class C02DemoSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(C02DemoSeeder.class);
    /** Demo customers reuse customer_demo's password hash, so no new secret is created or printed. */
    private static final String TEMPLATE_LOGIN = "customer_demo";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<WorkflowStartService> workflows;
    private final C02IntakeService intake;
    private final ObjectProvider<C02StaffDecisionPersistence> decisions;
    private final ObjectProvider<com.bankingax.core.notification.NotificationWriter> notifications;

    public C02DemoSeeder(JdbcTemplate jdbc, ObjectProvider<WorkflowStartService> workflows, C02IntakeService intake,
                         ObjectProvider<C02StaffDecisionPersistence> decisions, ObjectProvider<com.bankingax.core.notification.NotificationWriter> notifications) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.intake = intake;
        this.decisions = decisions;
        this.notifications = notifications;
    }

    /** How far a seeded Case goes after submit. Reservation states that normal use cannot reach on demand are written directly. */
    enum Stage { SUBMITTED, PROCEED_WAITING_MANUAL, PROCEED_EXPIRED_VISIT }

    record Scenario(String key, String loginId, String name, String email, C02IntakeService.Intake intake, Stage stage) {
        Scenario(String key, String loginId, String name, String email, C02IntakeService.Intake intake) { this(key, loginId, name, email, intake, Stage.SUBMITTED); }
    }

    private static List<C02IntakeService.HeirInput> delegatingHeirs(String applicant, String absent) {
        return List.of(heir("h1", applicant, "자녀", true, C02IntakeService.VisitStatus.ATTENDING),
                new C02IntakeService.HeirInput("h2", absent, LocalDate.of(1988, 4, 9), "자녀", false, false,
                        C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT, null));
    }

    static List<Scenario> scenarios() {
        var heirsA = List.of(
                heir("h1", "정미숙", "배우자", false, C02IntakeService.VisitStatus.ATTENDING),
                heir("h2", "이서연", "자녀", true, C02IntakeService.VisitStatus.ATTENDING),
                heir("h3", "이도현", "자녀", false, C02IntakeService.VisitStatus.UNCONFIRMED_ABSENT));
        // An absent heir delegating by power of attorney: the Case the bank's power-of-attorney changes concern.
        var heirsPolicy = List.of(
                heir("h1", "김민준", "자녀", true, C02IntakeService.VisitStatus.ATTENDING),
                new C02IntakeService.HeirInput("h2", "김서윤", LocalDate.of(1988, 4, 9), "자녀", false, false,
                        C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT, null));
        var heirsB = List.of(
                heir("h1", "정하늘", "자녀", true, C02IntakeService.VisitStatus.ATTENDING),
                heir("h2", "정하준", "자녀", false, C02IntakeService.VisitStatus.ATTENDING));
        return List.of(
                // Full path: staff review → result → notification → reservation and assignment.
                new Scenario("review", "demo_seoyeon", "이서연", "demo-seoyeon@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("이정호", LocalDate.of(1958, 2, 11), LocalDate.of(2026, 9, 28), 6_800_000L), heirsA)),
                // Customer cancellation before review starts (T-02), or the start/cancel race (T-03).
                new Scenario("cancel", "demo_haneul", "정하늘", "demo-haneul@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("정만수", LocalDate.of(1950, 5, 3), LocalDate.of(2026, 10, 2), 2_400_000L), heirsB)),
                // Submitted, review not started, power-of-attorney delegation: an impact candidate for the policy-change demo.
                new Scenario("policy", "demo_minjun", "김민준", "demo-minjun@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("김영수", LocalDate.of(1952, 7, 21), LocalDate.of(2026, 10, 12), 3_900_000L), heirsPolicy)),
                // A second submitted Case, so question, answer, re-check and decision can be rehearsed without using up the demo one.
                new Scenario("rehearsal", "demo_jiwoo", "오지우", "demo-jiwoo@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("오상철", LocalDate.of(1949, 3, 15), LocalDate.of(2026, 9, 3), 5_200_000L), delegatingHeirs("오지우", "오민재"))),
                // Result already notified, booking waiting for a branch staff member (the ranking cannot fail on demand).
                new Scenario("visit-manual", "demo_taemin", "강태민", "demo-taemin@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("강영식", LocalDate.of(1947, 11, 2), LocalDate.of(2026, 8, 21), 4_100_000L), delegatingHeirs("강태민", "강수아")), Stage.PROCEED_WAITING_MANUAL),
                // Result already notified, an unassigned booking expired; the customer sees why and can book again (and change it).
                new Scenario("visit-expired", "demo_yujin", "서유진", "demo-yujin@example.com", new C02IntakeService.Intake(
                        new C02IntakeService.DecedentInput("서동훈", LocalDate.of(1951, 6, 30), LocalDate.of(2026, 7, 19), 2_900_000L), delegatingHeirs("서유진", "서현우")), Stage.PROCEED_EXPIRED_VISIT),
                // Account only: the presenter does the whole intake live (chat → form → uploads checked by Spring).
                // One per rehearsal, because a customer has a single active C-02 until submit hands the WorkItem off.
                new Scenario("live1", "demo_live1", "윤서아", "demo-live1@example.com", null),
                new Scenario("live2", "demo_live2", "박지훈", "demo-live2@example.com", null),
                new Scenario("live3", "demo_live3", "한지민", "demo-live3@example.com", null));
    }

    private static C02IntakeService.HeirInput heir(String ref, String name, String relationship, boolean applicant, C02IntakeService.VisitStatus visit) {
        return new C02IntakeService.HeirInput(ref, name, null, relationship, applicant, false, visit, C02IntakeService.DelegationMethod.NONE, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        WorkflowStartService starter = workflows.getIfAvailable();
        if (starter == null) { log.warn("C-02 demo seed skipped: app.case-port=jpa is required."); return; }
        for (Scenario scenario : scenarios()) {
            try { seed(starter, scenario); }
            catch (RuntimeException exception) { log.warn("C-02 demo seed '{}' skipped: {}", scenario.key(), exception.getMessage()); }
        }
    }

    void seed(WorkflowStartService starter, Scenario s) {
        if (s.intake() == null) { log.info("C-02 demo customer '{}' ready (id {}).", s.loginId(), ensureCustomer(s)); return; }
        String marker = "demo-seed-c02-" + s.key();
        Integer done = jdbc.queryForObject("select count(*) from event_log where request_id=?", Integer.class, marker);
        if (done != null && done > 0) { log.info("C-02 demo seed '{}' already present.", s.key()); return; }
        long customerId = ensureCustomer(s);
        var workItem = starter.start(new WorkflowStartService.StartCommand(marker + "-start", customerId, null, TaskCode.INHERITANCE_DEPOSIT));
        var prepared = intake.prepare(customerId, workItem.workItemId(), marker + "-prepare", s.intake(), Map.of());
        if (prepared.caseView() == null) throw new IllegalStateException("prepare did not create a Case: " + prepared.submission());
        long caseId = prepared.caseView().caseId();
        int revision = prepared.caseView().revision();
        for (C02IntakeService.DocumentSlot slot : prepared.requiredDocuments()) {
            if (slot.requirement() != C02IntakeService.Requirement.REQUIRED) continue;
            revision = intake.uploadDocument(customerId, caseId, revision, marker + "-upload-" + slot.key(), slot.key(), DemoPdf.of(slot.key())).revision();
        }
        intake.recordConsentAndMockSignature(customerId, caseId, revision, true, true);
        // The staff Agent dedups by requestId, so it must be unique per Case, not just per scenario.
        var result = intake.submitForDemoSeed(customerId, caseId, revision, marker + "-submit-c" + caseId);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('SERVICE_CASE',?,'C02_DEMO_SEEDED',null,?,'SYSTEM',null,?,JSON_OBJECT('scenario',?,'customerLogin',?,'sms','SKIPPED_DEMO_SEED'))",
                String.valueOf(caseId), result.caseStatus(), marker, s.key(), s.loginId());
        log.info("C-02 demo seed '{}': case {} {} / precheck {}", s.key(), caseId, result.caseStatus(), result.reviewStatus());
        advance(s, marker, customerId, caseId);
    }

    /** Runs after the marker, so a failure here can leave a submitted Case but can never create a second one. */
    private void advance(Scenario s, String marker, long customerId, long caseId) {
        if (s.stage() == Stage.SUBMITTED) return;
        Map<String, Object> staff = jdbc.queryForMap("select id, branch_id from `user` where login_id='staff_demo' and role='STAFF'");
        long staffId = ((Number) staff.get("id")).longValue();
        int revision = jdbc.queryForObject("select revision from service_case where id=?", Integer.class, caseId);
        decisions.getObject().startReview(staffId, caseId, revision, marker + "-review");
        decisions.getObject().persist(staffId, caseId, revision, marker + "-decide", "PROCEED", "방문 예약을 진행해 주세요. 실제 지급 여부는 영업점에서 원본을 확인한 뒤 정해져요. (시연 데이터)",
                "REVIEW_PASSED", "시연 데이터: 예약 화면을 보여주기 위해 결과만 구성");
        boolean manual = s.stage() == Stage.PROCEED_WAITING_MANUAL;
        Timestamp at = Timestamp.from(manual ? nextVisit() : Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        jdbc.update("insert into visit_reservation(case_id,customer_id,branch_id,assigned_staff_id,starts_at,status) values(?,?,?,null,?,?)",
                caseId, customerId, staff.get("branch_id"), at, manual ? "NEEDS_MANUAL_ASSIGNMENT" : "EXPIRED");
        long reservationId = jdbc.queryForObject("select max(id) from visit_reservation where case_id=?", Long.class, caseId);
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload) values('VISIT_RESERVATION',?,?,null,?,'SYSTEM',null,?,JSON_OBJECT('caseId',?,'branchId',?,'reason','DEMO_SEED'))",
                String.valueOf(reservationId), manual ? "VISIT_NEEDS_MANUAL_ASSIGNMENT" : "VISIT_EXPIRED", manual ? "NEEDS_MANUAL_ASSIGNMENT" : "EXPIRED", marker + "-visit", caseId, staff.get("branch_id"));
        String number = "V-%06d".formatted(reservationId);
        notifications.getObject().store(customerId, caseId, null, manual ? "VISIT_PENDING_ASSIGNMENT" : "VISIT_EXPIRED", manual ? "방문 예약 요청이 접수됐어요" : "방문 예약이 만료되었어요",
                manual ? "예약번호 " + number + ". 영업점이 담당 직원을 배정하면 확정 안내를 드려요. 배정 전에는 예약 확정이 아니에요." : "예약번호 " + number + "는 담당 직원이 배정되기 전에 방문 시간이 지나 만료됐어요. 다시 예약할 수 있어요.",
                (manual ? "visit-pending-" : "visit-expired-") + reservationId);
    }

    /** 11:00 on the third weekday from now, so the visit stays outside the 24-hour lock through rehearsals. */
    private static Instant nextVisit() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        LocalDate date = LocalDate.now(seoul);
        for (int weekdays = 0; weekdays < 3; ) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) weekdays++;
        }
        return date.atTime(LocalTime.of(11, 0)).atZone(seoul).toInstant();
    }

    private long ensureCustomer(Scenario s) {
        List<Long> existing = jdbc.queryForList("select id from `user` where login_id=?", Long.class, s.loginId());
        if (!existing.isEmpty()) return existing.get(0);
        int inserted = jdbc.update("insert into `user`(login_id,password_hash,role,name,phone,email) select ?,password_hash,'CUSTOMER',?,'010-0000-0009',? from `user` where login_id=?",
                s.loginId(), s.name(), s.email(), TEMPLATE_LOGIN);
        if (inserted != 1) throw new IllegalStateException("template customer " + TEMPLATE_LOGIN + " is missing");
        return jdbc.queryForObject("select id from `user` where login_id=?", Long.class, s.loginId());
    }

    /** A small valid one-page PDF so the upload's magic-byte check and the staff "원본 보기" both work. */
    static final class DemoPdf implements MultipartFile {
        private final String name;
        private final byte[] bytes;

        private DemoPdf(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }

        static DemoPdf of(String slotKey) {
            String text = "BT /F1 20 Tf 60 760 Td (BANKING AX DEMO - SYNTHETIC DOCUMENT) Tj 0 -32 Td /F1 14 Tf (" + slotKey.replace("(", "").replace(")", "") + ") Tj 0 -24 Td (Not a real certificate. For demonstration only.) Tj ET";
            String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                    + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n"
                    + "4 0 obj<</Length " + text.length() + ">>stream\n" + text + "\nendstream endobj\n"
                    + "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n";
            return new DemoPdf("demo-" + slotKey.replace(':', '-').toLowerCase() + ".pdf", pdf.getBytes(StandardCharsets.US_ASCII));
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws java.io.IOException { Files.write(destination.toPath(), bytes); }
    }
}
