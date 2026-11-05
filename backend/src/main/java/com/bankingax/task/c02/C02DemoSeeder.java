package com.bankingax.task.c02;

import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.jpa.WorkflowStartService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
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

    public C02DemoSeeder(JdbcTemplate jdbc, ObjectProvider<WorkflowStartService> workflows, C02IntakeService intake) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.intake = intake;
    }

    record Scenario(String key, String loginId, String name, String email, C02IntakeService.Intake intake) { }

    static List<Scenario> scenarios() {
        var heirsA = List.of(
                heir("h1", "정미숙", "배우자", false, C02IntakeService.VisitStatus.ATTENDING),
                heir("h2", "이서연", "자녀", true, C02IntakeService.VisitStatus.ATTENDING),
                heir("h3", "이도현", "자녀", false, C02IntakeService.VisitStatus.UNCONFIRMED_ABSENT));
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
