package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "app.case-port=jpa")
class B01EligibilityMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_eligibility_it")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired B01EligibilityService service;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("delete from document_extraction");
        jdbc.update("delete from document_file");
        jdbc.update("delete from required_document");
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values "
                + "(1,'b01-validator','x','CUSTOMER','one'),(2,'other-customer','x','CUSTOMER','two')");
        jdbc.update("insert into workflow(id,customer_id,status) values (10,1,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-validation-work')");
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/seed/b01_test_loans.sql"));
        }
        jdbc.update("""
                insert into loan(customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,
                  acceleration_declared,acceleration_date,repayment_order)
                values (1,'MOCK-B01-NOT-ACC','synthetic non-accelerated',1000,900,90,false,null,'INTEREST_FIRST'),
                       (2,'MOCK-B01-FOREIGN','synthetic foreign',1000,900,90,true,'2026-08-01','INTEREST_FIRST')
                """);
    }

    @Test
    void evaluatesT01ThroughT05InRequiredRuleOrder() throws Exception {
        assertResult("MOCK-B01-T01", "PRINCIPAL_FIRST", B01EligibilityService.RuleCode.ELIGIBLE, false);
        assertResult("MOCK-B01-T02", "PRINCIPAL_FIRST", B01EligibilityService.RuleCode.OUTSOURCED_LOAN, false);
        assertResult("MOCK-B01-T03", "PRINCIPAL_FIRST", B01EligibilityService.RuleCode.AGREEMENT_LOAN, false);
        assertResult("MOCK-B01-T04", "INTEREST_FIRST", B01EligibilityService.RuleCode.ELIGIBLE, false);
        assertResult("MOCK-B01-T05", "PRINCIPAL_FIRST", B01EligibilityService.RuleCode.ELIGIBLE, true);
    }

    @Test
    void distinguishesAccelerationFromOverdueDaysAndDetectsAlreadyApplied() throws Exception {
        long notAccelerated = loanId("MOCK-B01-NOT-ACC");
        storeMatchingExtraction("MOCK-B01-NOT-ACC", "PRINCIPAL_FIRST");
        assertThat(service.validate(1, 100, notAccelerated, "PRINCIPAL_FIRST").ruleCode())
                .isEqualTo(B01EligibilityService.RuleCode.NOT_ACCELERATED);

        resetDocument();
        long t01 = loanId("MOCK-B01-T01");
        storeMatchingExtraction("MOCK-B01-T01", "INTEREST_FIRST");
        assertThat(service.validate(1, 100, t01, "INTEREST_FIRST").ruleCode())
                .isEqualTo(B01EligibilityService.RuleCode.ALREADY_APPLIED);
    }

    @Test
    void rejectsForeignLoanWithoutRevealingIt() {
        long foreign = loanId("MOCK-B01-FOREIGN");
        DomainException error = assertThrows(DomainException.class,
                () -> service.validate(1, 100, foreign, "PRINCIPAL_FIRST"));
        assertThat(error.code()).isEqualTo("LOAN_NOT_AVAILABLE");
    }

    @Test
    void reportsEachOcrMismatchAndMultipleLoans() throws Exception {
        long loanId = loanId("MOCK-B01-T01");
        LoanFixture loan = loan("MOCK-B01-T01");

        storeExtraction(List.of(new B01OcrPort.LoanField("OTHER", loan.balance(), loan.accelerationDate())),
                "PRINCIPAL_FIRST", "SUCCEEDED");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.ACCOUNT_NUMBER_MISMATCH);

        resetDocument();
        storeExtraction(List.of(new B01OcrPort.LoanField(loan.loanNo(), loan.balance().add(BigDecimal.ONE),
                loan.accelerationDate())), "PRINCIPAL_FIRST", "SUCCEEDED");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.BALANCE_MISMATCH);

        resetDocument();
        storeExtraction(List.of(new B01OcrPort.LoanField(loan.loanNo(), loan.balance(),
                loan.accelerationDate().plusDays(1))), "PRINCIPAL_FIRST", "SUCCEEDED");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.ACCELERATION_DATE_MISMATCH);

        resetDocument();
        storeExtraction(List.of(new B01OcrPort.LoanField(loan.loanNo(), loan.balance(), loan.accelerationDate()),
                new B01OcrPort.LoanField("MOCK-B01-T02", BigDecimal.ONE, LocalDate.of(2026, 1, 1))),
                "PRINCIPAL_FIRST", "SUCCEEDED");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.OCR_LOAN_COUNT_INVALID);
    }

    @Test
    void reportsRequestedOrderMismatchFailureAndMissingExtraction() throws Exception {
        long loanId = loanId("MOCK-B01-T01");
        storeMatchingExtraction("MOCK-B01-T01", "INTEREST_FIRST");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.REQUESTED_ORDER_MISMATCH);

        resetDocument();
        LoanFixture loan = loan("MOCK-B01-T01");
        storeExtraction(List.of(new B01OcrPort.LoanField(loan.loanNo(), loan.balance(), loan.accelerationDate())),
                "PRINCIPAL_FIRST", "FAILED_NEEDS_STAFF");
        assertMismatch(loanId, B01EligibilityService.MismatchCode.OCR_NOT_SUCCEEDED);

        resetDocument();
        var missing = service.validate(1, 100, loanId, "PRINCIPAL_FIRST");
        assertThat(missing.ocr().mismatchCodes()).containsExactly(B01EligibilityService.MismatchCode.OCR_NOT_AVAILABLE);
    }

    @Test
    void mockSuccessNeverClaimsSignatureAuthenticityOrAutomaticExecution() throws Exception {
        long loanId = loanId("MOCK-B01-T01");
        storeMatchingExtraction("MOCK-B01-T01", "PRINCIPAL_FIRST");
        var result = service.validate(1, 100, loanId, "PRINCIPAL_FIRST");
        assertThat(result.ruleCode()).isEqualTo(B01EligibilityService.RuleCode.ELIGIBLE);
        assertThat(result.ocr().matched()).isTrue();
        assertThat(result.verification().signaturesVerified()).isFalse();
        assertThat(result.verification().authenticityVerified()).isFalse();
        assertThat(result.verification().manualVerificationRequired()).isTrue();
        assertThat(result.automaticProcessingAllowed()).isFalse();
        assertThat(result.householdLoanClassification()).isEqualTo("NOT_AVAILABLE_IN_SCHEMA");
        assertThat(jdbc.queryForObject("select repayment_order from loan where id=?", String.class, loanId))
                .isEqualTo("INTEREST_FIRST");
        assertThat(jdbc.queryForObject("select version from loan where id=?", Integer.class, loanId)).isZero();
    }

    private void assertResult(String loanNo, String requested, B01EligibilityService.RuleCode expected,
                              boolean confirmation) throws Exception {
        resetDocument();
        storeMatchingExtraction(loanNo, requested);
        var result = service.validate(1, 100, loanId(loanNo), requested);
        assertThat(result.ruleCode()).isEqualTo(expected);
        assertThat(result.ocr().matched()).isTrue();
        assertThat(result.additionalConfirmationRequired()).isEqualTo(confirmation);
        assertThat(result.automaticProcessingAllowed()).isFalse();
    }

    private void assertMismatch(long loanId, B01EligibilityService.MismatchCode mismatch) {
        var result = service.validate(1, 100, loanId, "PRINCIPAL_FIRST");
        assertThat(result.ocr().matched()).isFalse();
        assertThat(result.ocr().mismatchCodes()).contains(mismatch);
        assertThat(result.automaticProcessingAllowed()).isFalse();
    }

    private void storeMatchingExtraction(String loanNo, String requested) throws Exception {
        LoanFixture loan = loan(loanNo);
        storeExtraction(List.of(new B01OcrPort.LoanField(loan.loanNo(), loan.balance(), loan.accelerationDate())),
                requested, "SUCCEEDED");
    }

    private void storeExtraction(List<B01OcrPort.LoanField> loans, String requested, String status) throws Exception {
        jdbc.update("insert into required_document(id,work_item_id,document_type_code,requirement,status) "
                + "values(800,100,'B01_REPAYMENT_ORDER_APPLICATION','REQUIRED','UPLOADED')");
        jdbc.update("""
                insert into document_file(id,required_document_id,version,is_latest,original_name,extension,
                  size_bytes,storage_path,content_type,checksum,case_revision,uploaded_by)
                values(900,800,1,true,'fixture.pdf','pdf',10,
                  '00000000-0000-0000-0000-000000000900.pdf','application/pdf',repeat('a',64),0,1)
                """);
        var result = new B01OcrPort.OcrResult("B01_REPAYMENT_ORDER_APPLICATION",
                "B01_REPAYMENT_ORDER_APPLICATION", "MOCK", status,
                new B01OcrPort.Fields(loans, requested),
                new B01OcrPort.SignatureChecks("NOT_VERIFIED", "NOT_VERIFIED"), false, true,
                "SUCCEEDED".equals(status) ? null : "MOCK_FIXTURE_NOT_REGISTERED");
        jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,extracted,failure_reason) "
                        + "values(900,null,null,'MOCK',?,cast(? as json),?)",
                status, json.writeValueAsString(result), result.failureReason());
    }

    private void resetDocument() {
        jdbc.update("delete from document_extraction");
        jdbc.update("delete from document_file");
        jdbc.update("delete from required_document");
    }

    private long loanId(String loanNo) {
        return jdbc.queryForObject("select id from loan where loan_no=?", Long.class, loanNo);
    }

    private LoanFixture loan(String loanNo) {
        return jdbc.queryForObject("select loan_no,balance,acceleration_date from loan where loan_no=?",
                (rs, row) -> new LoanFixture(rs.getString(1), rs.getBigDecimal(2),
                        rs.getObject(3, LocalDate.class)), loanNo);
    }

    private record LoanFixture(String loanNo, BigDecimal balance, LocalDate accelerationDate) { }
}
