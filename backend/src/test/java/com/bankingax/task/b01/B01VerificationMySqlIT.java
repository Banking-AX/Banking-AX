package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {com.bankingax.BankingAxApplication.class, B01VerificationMySqlIT.SmsConfiguration.class})
class B01VerificationMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_verification_it").withUsername("test").withPassword("test");
    static final Path DOCUMENTS = temporary("b01-verification-docs-");
    static final Path SIGNATURES = temporary("b01-verification-signatures-");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl); p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword); p.add("app.case-port", () -> "jpa");
        p.add("app.b01.file-storage-root", DOCUMENTS::toString);
        p.add("app.b01.signature-storage-root", SIGNATURES::toString);
        p.add("app.sms.otp-hmac-secret", () -> "test-only-separate-otp-hmac-secret-more-than-32-bytes");
        p.add("app.sms.resend-seconds", () -> "0");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired B01DocumentService documents;
    @Autowired B01VerificationService service;
    @Autowired B01ProgressService progress;
    @Autowired CapturingSender sender;
    long documentFileId;

    @BeforeEach void seed() throws Exception {
        clearTestSignatureDirectory();
        jdbc.update("delete from event_log"); jdbc.update("delete from consent_record");
        jdbc.update("delete from document_extraction"); jdbc.update("delete from document_file");
        jdbc.update("delete from required_document"); jdbc.update("delete from loan");
        jdbc.update("delete from work_item"); jdbc.update("delete from workflow"); jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,phone) values "
                + "(1,'b01-verify','x','CUSTOMER','one','01012345678'),(2,'other','x','CUSTOMER','two','01099999999')");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,1,'RUNNING'),(20,2,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-verify-work'),"
                + "(200,20,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-other-work')");
        jdbc.update("insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,"
                + "acceleration_declared,acceleration_date,repayment_order) values "
                + "(501,1,'MOCK-B01-T01','synthetic',30000000,26500000,62,true,'2026-08-31','INTEREST_FIRST')");
        progress.saveSelection(1,100,new B01ProgressService.SelectionCommand(
                "seed-selection",501,"PRINCIPAL_FIRST"));
        byte[] fixture = new ClassPathResource("b01/mock-ocr/b01-t01-application.pdf").getInputStream().readAllBytes();
        documentFileId = documents.upload(1,100,new MockMultipartFile("file","fixture.pdf","application/pdf",fixture)).documentFileId();
        sender.clear();
    }

    @Test void completesConsentSignatureSmsAndRestoresStatusWithoutChangingLoan() throws Exception {
        consent("consent-1");
        assertThat(jdbc.queryForObject("select count(*) from consent_record where work_item_id=100 and consent_type=? and agreed=true",Integer.class,
                B01VerificationService.CONSENT_TYPE)).isOne();
        var signature = service.signature(1,100,"signature-1",png(true));
        assertThat(signature.mockSignatureCompleted()).isTrue();
        assertThat(Files.list(SIGNATURES).count()).isOne();
        var issue = service.requestSms(1,100,"sms-request-1");
        var retried = service.requestSms(1,100,"sms-request-1");
        assertThat(retried.challengeId()).isEqualTo(issue.challengeId());
        assertThat(sender.sendCount.get()).isOne();
        assertThat(issue.maskedPhone()).isEqualTo("010-****-5678");
        assertThat(sender.phone.get()).isEqualTo("01012345678");
        assertThat(service.verifySms(1,100,"sms-verify-1",issue.challengeId(),sender.otp()).verified()).isTrue();
        assertThat(service.status(1,100).currentStep()).isEqualTo("READY_FOR_FINAL_VALIDATION");
        var restored = progress.context(1,100);
        assertThat(restored.selectedLoanId()).isEqualTo(501L);
        assertThat(restored.requestedRepaymentOrder()).isEqualTo("PRINCIPAL_FIRST");
        assertThat(restored.applicationDocument().documentFileId()).isEqualTo(documentFileId);
        assertThat(restored.applicationDocument().displayName()).isEqualTo("fixture.pdf");
        assertThat(restored.applicationDocument().ocr().status()).isEqualTo("SUCCEEDED");
        assertThat(restored.applicationDocument().ocr().loans().get(0).maskedAccountNumber()).isEqualTo("****-T01");
        assertThat(restored.consentAgreed()).isTrue();
        assertThat(restored.mockSignatureCompleted()).isTrue();
        assertThat(restored.smsVerified()).isTrue();
        assertThat(restored.currentStep()).isEqualTo("READY_TO_EXECUTE");
        assertThat(restored.readyToExecute()).isTrue();
        assertThat(jdbc.queryForObject("select repayment_order from loan where id=501",String.class)).isEqualTo("INTEREST_FIRST");
        assertThat(jdbc.queryForObject("select version from loan where id=501",Integer.class)).isZero();
        String draft=jdbc.queryForObject("select draft_data from work_item where id=100",String.class);
        assertThat(draft).doesNotContain(sender.otp());
    }

    @Test void enforcesOrderOwnershipImageValidationAndSignatureIdempotency() throws Exception {
        assertThrows(DomainException.class, () -> service.signature(1,100,"before-consent",png(true)));
        consent("consent-2");
        assertThrows(DomainException.class, () -> service.signature(2,100,"foreign",png(true)));
        assertThrows(DomainException.class, () -> service.signature(1,100,"bad",new MockMultipartFile("file","x.png","image/png","bad".getBytes())));
        assertThrows(DomainException.class, () -> service.signature(1,100,"blank",png(false)));
        service.signature(1,100,"same-signature",png(true));
        service.signature(1,100,"same-signature",png(true));
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='B01_MOCK_SIGNATURE_COMPLETED'",Integer.class)).isOne();
    }

    @Test void resendInvalidatesOldOtpAndSuccessfulOtpCannotBeReused() throws Exception {
        consent("consent-3"); service.signature(1,100,"signature-3",png(true));
        var first=service.requestSms(1,100,"sms-1"); String old=sender.otp();
        var second=service.requestSms(1,100,"sms-2"); String current=sender.otp();
        assertThat(second.challengeId()).isNotEqualTo(first.challengeId());
        assertThrows(DomainException.class,()->service.verifySms(1,100,"verify-old",first.challengeId(),old));
        service.verifySms(1,100,"verify-current",second.challengeId(),current);
        assertThrows(DomainException.class,()->service.verifySms(1,100,"reuse",second.challengeId(),current));
    }

    @Test void persistsFailedAttemptsLocksChallengeAndRejectsExpiredCode() throws Exception {
        consent("consent-5"); service.signature(1,100,"signature-5",png(true));
        var issue=service.requestSms(1,100,"sms-attempts");
        String wrong=sender.otp().equals("000000")?"999999":"000000";
        for(int attempt=1;attempt<=4;attempt++) {
            DomainException error=assertThrows(DomainException.class,
                    ()->service.verifySms(1,100,"wrong-"+System.nanoTime(),issue.challengeId(),wrong));
            assertThat(error.code()).isEqualTo("OTP_INVALID");
        }
        DomainException locked=assertThrows(DomainException.class,
                ()->service.verifySms(1,100,"wrong-final",issue.challengeId(),wrong));
        assertThat(locked.code()).isEqualTo("OTP_ATTEMPTS_EXCEEDED");
        assertThat(jdbc.queryForObject("select json_extract(draft_data,'$.smsAttempts') from work_item where id=100",Integer.class)).isEqualTo(5);

        jdbc.update("update work_item set draft_data=json_remove(draft_data,'$.smsRequestedAt') where id=100");
        var fresh=service.requestSms(1,100,"sms-expiry");
        jdbc.update("update work_item set draft_data=json_set(draft_data,'$.smsExpiresAt','2000-01-01T00:00:00Z') where id=100");
        DomainException expired=assertThrows(DomainException.class,
                ()->service.verifySms(1,100,"expired",fresh.challengeId(),sender.otp()));
        assertThat(expired.code()).isEqualTo("OTP_EXPIRED");
    }

    @Test void changedApplicationInvalidatesPriorProgress() throws Exception {
        consent("consent-4"); service.signature(1,100,"signature-4",png(true));
        jdbc.update("update document_file set checksum=repeat('b',64) where id=?",documentFileId);
        assertThat(service.status(1,100).currentStep()).isEqualTo("APPLICATION_CHANGED");
        assertThrows(DomainException.class,()->service.requestSms(1,100,"sms-after-change"));
    }

    @Test void persistsSelectionAndEligibilityForRefreshAndAllowsHeldContextRead() {
        progress.saveSelection(1,100,new B01ProgressService.SelectionCommand(
                "select-1",501,"PRINCIPAL_FIRST"));
        var selected = progress.context(1,100);
        assertThat(selected.selectedLoanId()).isEqualTo(501L);
        assertThat(selected.requestedRepaymentOrder()).isEqualTo("PRINCIPAL_FIRST");
        assertThat(selected.applicationDocument().documentFileId()).isEqualTo(documentFileId);
        assertThat(selected.lastEligibility()).isNull();
        assertThat(selected.currentStep()).isEqualTo("ELIGIBILITY_VALIDATION");

        progress.validateAndSave(1,100,501,"PRINCIPAL_FIRST");
        var validated = progress.context(1,100);
        assertThat(validated.lastEligibility()).isNotNull();
        assertThat(validated.lastEligibility().ruleCode()).isEqualTo(B01EligibilityService.RuleCode.ELIGIBLE);
        assertThat(validated.lastEligibility().ocr().matched()).isTrue();
        assertThat(validated.currentStep()).isEqualTo("CONSENT");

        jdbc.update("update work_item set status='ON_HOLD',resume_step='CONSENT' where id=100");
        var held = progress.context(1,100);
        assertThat(held.workItemVersion()).isEqualTo(
                jdbc.queryForObject("select version from work_item where id=100", Integer.class));
        assertThat(held.workItemStatus()).isEqualTo("ON_HOLD");
        assertThat(held.resumeStep()).isEqualTo("CONSENT");
        assertThat(held.currentStep()).isEqualTo("CONSENT");
    }

    @Test void changingSelectionOrDocumentInvalidatesConsentSignatureAndSms() throws Exception {
        consent("consent-change");
        service.signature(1,100,"signature-change",png(true));
        var issue=service.requestSms(1,100,"sms-change");
        service.verifySms(1,100,"verify-change",issue.challengeId(),sender.otp());

        progress.saveSelection(1,100,new B01ProgressService.SelectionCommand(
                "change-order",501,"INTEREST_FIRST"));
        var changed = progress.context(1,100);
        assertThat(changed.consentAgreed()).isFalse();
        assertThat(changed.mockSignatureCompleted()).isFalse();
        assertThat(changed.smsVerified()).isFalse();
        assertThat(changed.lastEligibility()).isNull();
        assertThat(changed.applicationDocument()).isNull();
        DomainException staleDocument = assertThrows(DomainException.class,
                () -> progress.validateAndSave(1,100,501,"INTEREST_FIRST"));
        assertThat(staleDocument.code()).isEqualTo("B01_APPLICATION_NOT_CURRENT");

        progress.saveSelection(1,100,new B01ProgressService.SelectionCommand(
                "restore-order",501,"PRINCIPAL_FIRST"));
        documentFileId = documents.upload(1,100,new MockMultipartFile("file","restored.pdf","application/pdf",
                new ClassPathResource("b01/mock-ocr/b01-t01-application.pdf").getInputStream().readAllBytes()))
                .documentFileId();
        progress.validateAndSave(1,100,501,"PRINCIPAL_FIRST");
        consent("consent-document-change");
        service.signature(1,100,"signature-document-change",png(true));
        documents.upload(1,100,new MockMultipartFile("file","replacement.pdf","application/pdf",
                new ClassPathResource("b01/mock-ocr/b01-t01-application.pdf").getInputStream().readAllBytes()));
        var replaced = progress.context(1,100);
        assertThat(replaced.applicationDocument().version()).isEqualTo(3);
        assertThat(replaced.applicationDocument().displayName()).isEqualTo("replacement.pdf");
        assertThat(replaced.consentAgreed()).isFalse();
        assertThat(replaced.mockSignatureCompleted()).isFalse();
        assertThat(replaced.smsVerified()).isFalse();
        assertThat(replaced.currentStep()).isEqualTo("ELIGIBILITY_VALIDATION");
    }

    @Test void rejectsForeignAndMissingWorkItemContext() {
        DomainException foreign = assertThrows(DomainException.class, () -> progress.context(2,100));
        DomainException missing = assertThrows(DomainException.class, () -> progress.context(1,999));
        assertThat(foreign.code()).isEqualTo("WORK_ITEM_NOT_AVAILABLE");
        assertThat(missing.code()).isEqualTo("WORK_ITEM_NOT_AVAILABLE");
    }

    private void consent(String request) {
        service.consent(1,100,new B01VerificationService.ConsentCommand(request,501,"PRINCIPAL_FIRST",
                documentFileId,B01VerificationService.NOTICE_VERSION,true));
    }
    private static MockMultipartFile png(boolean ink) throws Exception {
        BufferedImage image=new BufferedImage(160,60,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,160,60);
        if(ink){g.setColor(Color.BLACK);g.drawLine(10,40,145,15);g.drawLine(20,45,130,20);} g.dispose();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(image,"png",out);
        return new MockMultipartFile("file","signature.png","image/png",out.toByteArray());
    }
    private static Path temporary(String prefix){try{return Files.createTempDirectory(prefix);}catch(Exception e){throw new ExceptionInInitializerError(e);}}

    private static void clearTestSignatureDirectory() throws Exception {
        Path testRoot = SIGNATURES.toRealPath();
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!testRoot.startsWith(systemTemp)
                || !testRoot.getFileName().toString().startsWith("b01-verification-signatures-")) {
            throw new IllegalStateException("Refusing to clean a non-test signature directory: " + testRoot);
        }
        try (var paths = Files.walk(testRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(testRoot))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    });
        }
    }

    @TestConfiguration static class SmsConfiguration {
        @Bean @Primary CapturingSender capturingSender(){return new CapturingSender();}
    }
    static final class CapturingSender implements B01SmsSender {
        final AtomicReference<String> phone=new AtomicReference<>(), text=new AtomicReference<>();
        final AtomicInteger sendCount=new AtomicInteger();
        public SendReceipt send(String phone,String text){this.phone.set(phone);this.text.set(text);sendCount.incrementAndGet();return SendReceipt.accepted("TEST");}
        String otp(){String value=text.get();return value.substring(value.indexOf("는 ")+2,value.indexOf("입니다"));}
        void clear(){phone.set(null);text.set(null);sendCount.set(0);}
    }
}
