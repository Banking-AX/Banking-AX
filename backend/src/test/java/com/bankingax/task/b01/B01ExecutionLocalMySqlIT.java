package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * B-01 execution and its web notification on a disposable Flyway schema, for machines where Testcontainers cannot
 * reach Docker. Never point T2_VERIFY_DB_URL at the shared banking_ax database: every test truncates.
 */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {com.bankingax.BankingAxApplication.class, B01VerificationMySqlIT.SmsConfiguration.class})
class B01ExecutionLocalMySqlIT {
    private static final String NOTIFICATIONS = "select count(*) from notification where work_item_id=100 and type='B01_RESULT'";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path documents = Files.createTempDirectory("b01-execution-docs-");
        Path signatures = Files.createTempDirectory("b01-execution-signatures-");
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.b01.file-storage-root", documents::toString);
        registry.add("app.b01.signature-storage-root", signatures::toString);
        registry.add("app.sms.otp-hmac-secret", () -> "test-only-separate-otp-hmac-secret-more-than-32-bytes");
        registry.add("app.sms.resend-seconds", () -> "0");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired B01DocumentService documents;
    @Autowired B01VerificationService verification;
    @Autowired B01ProgressService progress;
    @Autowired B01ExecutionService execution;
    @Autowired B01VerificationMySqlIT.CapturingSender sender;

    @BeforeEach void seed() throws Exception {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("notification", "event_log", "task_result", "consent_record", "document_extraction",
                        "document_file", "required_document", "loan", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,phone) values "
                + "(1,'b01-exec','x','CUSTOMER','one','01012345678'),(2,'other','x','CUSTOMER','two','01099999999')");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,1,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values"
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-exec-work')");
        jdbc.update("insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,"
                + "acceleration_declared,acceleration_date,repayment_order) values "
                + "(501,1,'MOCK-B01-T01','synthetic',30000000,26500000,62,true,'2026-08-31','INTEREST_FIRST')");
        progress.saveSelection(1, 100, new B01ProgressService.SelectionCommand("seed", 501, "PRINCIPAL_FIRST"));
        byte[] fixture = new ClassPathResource("b01/mock-ocr/b01-t01-application.pdf").getInputStream().readAllBytes();
        long documentFileId = documents.upload(1, 100,
                new MockMultipartFile("file", "fixture.pdf", "application/pdf", fixture)).documentFileId();
        progress.validateAndSave(1, 100, 501, "PRINCIPAL_FIRST");
        verification.consent(1, 100, new B01VerificationService.ConsentCommand("consent", 501, "PRINCIPAL_FIRST",
                documentFileId, B01VerificationService.NOTICE_VERSION, true));
        sender.clear();
    }

    private void completeVerification() throws Exception {
        verification.signature(1, 100, "signature", signature());
        var issue = verification.requestSms(1, 100, "sms");
        verification.verifySms(1, 100, "sms-verify", issue.challengeId(), sender.otp());
    }

    @Test void completionChangesTheLoanAndStoresOneNotification() throws Exception {
        completeVerification();

        var result = execution.execute(1, 100, "execute-1");

        assertThat(result.status()).isEqualTo("DONE");
        assertThat(result.consentAt()).isNotNull();
        assertThat(jdbc.queryForObject("select repayment_order from loan where id=501", String.class)).isEqualTo("PRINCIPAL_FIRST");
        assertThat(jdbc.queryForObject("select status from work_item where id=100", String.class)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select status from workflow where id=10", String.class)).isEqualTo("COMPLETED");
        var row = jdbc.queryForMap("select customer_id,task_result_id,title,content from notification where work_item_id=100");
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(1);
        assertThat(row.get("task_result_id")).isNotNull();
        assertThat(row.get("content").toString()).contains("****-T01", "이자 우선", "원금 우선", "모의").doesNotContain("MOCK-B01-T01");
    }

    @Test void replayKeepsOneResultAndOneNotification() throws Exception {
        completeVerification();

        var first = execution.execute(1, 100, "execute-1");
        var second = execution.execute(1, 100, "execute-2");

        assertThat(second.processedAt()).isEqualTo(first.processedAt());
        assertThat(jdbc.queryForObject(NOTIFICATIONS, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=100", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='B01_EXECUTED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select version from loan where id=501", Integer.class)).isOne();
    }

    @Test void unverifiedExecutionLeavesNoChangeOrNotification() {
        assertCode("B01_NOT_READY_TO_EXECUTE", 1);
        assertUnchanged();
    }

    @Test void anotherCustomerCannotExecute() throws Exception {
        completeVerification();
        assertCode("WORK_ITEM_NOT_AVAILABLE", 2);
        assertUnchanged();
    }

    @Test void loanChangedAfterVerificationIsNotExecuted() throws Exception {
        completeVerification();
        jdbc.update("update loan set outsourced=true where id=501");

        assertThat(assertThrows(DomainException.class, () -> execution.execute(1, 100, "execute-1")).code())
                .isIn("B01_NOT_READY_TO_EXECUTE", "B01_REVALIDATION_FAILED");
        assertUnchanged();
    }

    private void assertCode(String code, long customerId) {
        assertThat(assertThrows(DomainException.class, () -> execution.execute(customerId, 100, "execute-1")).code()).isEqualTo(code);
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("select repayment_order from loan where id=501", String.class)).isEqualTo("INTEREST_FIRST");
        assertThat(jdbc.queryForObject("select status from work_item where id=100", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(NOTIFICATIONS, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_result", Integer.class)).isZero();
    }

    private static MockMultipartFile signature() throws Exception {
        BufferedImage image = new BufferedImage(320, 120, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 320, 120);
        graphics.setColor(Color.BLACK);
        graphics.drawLine(20, 60, 300, 70);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("file", "signature.png", "image/png", bytes.toByteArray());
    }
}
