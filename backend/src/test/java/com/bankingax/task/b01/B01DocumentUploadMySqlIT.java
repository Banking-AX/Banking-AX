package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.DomainException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class B01DocumentUploadMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_document_it")
            .withUsername("test").withPassword("test");
    static final Path STORAGE = temporaryStorage();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("app.b01.file-storage-root", STORAGE::toString);
        properties.add("app.case-port", () -> "jpa");
        properties.add("app.auth.enabled", () -> "true");
        properties.add("app.auth.jwt-secret", () -> "b01-document-test-secret-material-that-is-longer-than-sixty-four-characters-1234");
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired B01DocumentService service;

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
                + "(1,'b01-one','x','CUSTOMER','one'),(2,'b01-two','x','CUSTOMER','two')");
        jdbc.update("insert into workflow(id,customer_id,status) values (10,1,'RUNNING'),(20,2,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-doc-100'),"
                + "(200,20,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-doc-200'),"
                + "(300,10,'REPAYMENT_ORDER_CHANGE',2,'ON_HOLD','b01-doc-300')");
        jdbc.update("""
                insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,
                  acceleration_declared,acceleration_date,repayment_order)
                values(501,1,'MOCK-B01-T01','synthetic household-loan assumption',30000000,26500000,62,
                  true,'2026-08-31','INTEREST_FIRST')
                """);
        if (Files.exists(STORAGE)) {
            try (var paths = Files.walk(STORAGE)) {
                paths.sorted(java.util.Comparator.reverseOrder()).filter(path -> !path.equals(STORAGE))
                        .forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
            }
        }
    }

    @Test
    void customerUploadsRecognizedFixtureAndPersistsSafeOcrResult() throws Exception {
        selectLoan(501, "PRINCIPAL_FIRST");
        byte[] fixture = fixture();
        var response = http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(new MockMultipartFile("file", "application.pdf", "application/pdf", fixture))
                        .param("requestId", "b01-upload-1")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.method").value("MOCK"))
                .andExpect(jsonPath("$.data.fields.loans[0].maskedAccountNumber").value("****-T01"))
                .andExpect(jsonPath("$.data.signatureChecks.debtorSignatureField").value("NOT_VERIFIED"))
                .andExpect(jsonPath("$.data.visualChecks.alignmentStatus").value("ALIGNED"))
                .andExpect(jsonPath("$.data.visualChecks.selectedOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.visualChecks.debtorSignatureTrace").value("TRACE_PRESENT"))
                .andExpect(jsonPath("$.data.authenticityVerified").value(false))
                .andExpect(jsonPath("$.data.manualVerificationRequired").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("MOCK-B01-T01").doesNotContain("storage_path");
        assertThat(count("select count(*) from document_file")).isEqualTo(1);
        assertThat(count("select count(*) from document_extraction where case_id is null and revision is null "
                + "and method='MOCK' and status='SUCCEEDED'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(extracted,'$.fields.loans[0].accountNumber')) "
                + "from document_extraction", String.class)).isEqualTo("MOCK-B01-T01");
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(extracted,'$.visualChecks.selectedOrder')) "
                + "from document_extraction", String.class)).isEqualTo("PRINCIPAL_FIRST");

        String validation = http.perform(post("/api/v1/customer/b01/work-items/{id}/eligibility", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b01-validate-1","loanId":501,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleCode").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.ocr.matched").value(true))
                .andExpect(jsonPath("$.data.automaticProcessingAllowed").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(validation).doesNotContain("MOCK-B01-T01");
    }

    @Test
    void validationUsesThePersistedLoanSelectionAndRejectsAnUnselectedLoan() throws Exception {
        jdbc.update("""
                insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,
                  acceleration_declared,acceleration_date,repayment_order)
                values(502,1,'MOCK-B01-OTHER','other synthetic loan',30000000,26500000,62,
                  true,'2026-08-31','INTEREST_FIRST')
                """);
        selectLoan(501, "PRINCIPAL_FIRST");
        http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(new MockMultipartFile("file", "application.pdf", "application/pdf", fixture()))
                        .param("requestId", "b01-upload-selected")
                        .with(customer(1)))
                .andExpect(status().isOk());

        http.perform(post("/api/v1/customer/b01/work-items/{id}/eligibility", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b01-validate-stale","loanId":502,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("B01_SELECTION_NOT_CURRENT"));

        assertThat(jdbc.queryForObject("select json_unquote(json_extract(draft_data,'$.selectedLoanId')) "
                + "from work_item where id=100", String.class)).isEqualTo("501");

        http.perform(post("/api/v1/customer/b01/work-items/{id}/eligibility", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b01-validate-selected","loanId":501,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId").value(501))
                .andExpect(jsonPath("$.data.ruleCode").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.ocr.matched").value(true));
    }

    @Test
    void validationRejectsAnApplicationWhenNoLoanWasSelected() throws Exception {
        http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(new MockMultipartFile("file", "application.pdf", "application/pdf", fixture()))
                        .param("requestId", "b01-upload-unselected")
                        .with(customer(1)))
                .andExpect(status().isOk());

        http.perform(post("/api/v1/customer/b01/work-items/{id}/eligibility", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b01-validate-unselected","loanId":501,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("B01_SELECTION_NOT_CURRENT"));
    }

    @Test
    void unregisteredContentIsRecordedForManualReviewAndFileIsRetained() throws Exception {
        byte[] unknown = "%PDF-1.4\nunknown synthetic B01 form\n%%EOF".getBytes();
        http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(new MockMultipartFile("file", "unknown.pdf", "application/pdf", unknown))
                        .param("requestId", "b01-upload-unknown")
                        .with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED_NEEDS_STAFF"))
                .andExpect(jsonPath("$.data.manualVerificationRequired").value(true));

        assertThat(count("select count(*) from document_file")).isEqualTo(1);
        assertThat(count("select count(*) from document_extraction where status='FAILED_NEEDS_STAFF' "
                + "and failure_reason='MOCK_FIXTURE_NOT_REGISTERED'")).isEqualTo(1);
    }

    @Test
    void extractionReplayAndConcurrentCallsDoNotCreateDuplicates() throws Exception {
        B01DocumentService.UploadResult uploaded = service.upload(1, 100,
                new MockMultipartFile("file", "fixture.pdf", "application/pdf", fixture()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> service.extractExisting(1, 100, uploaded.documentFileId()));
            var two = executor.submit(() -> service.extractExisting(1, 100, uploaded.documentFileId()));
            assertThat(one.get().status()).isEqualTo("SUCCEEDED");
            assertThat(two.get().status()).isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("select count(*) from document_extraction where document_file_id="
                + uploaded.documentFileId())).isEqualTo(1);
    }

    @Test
    void sequentialRetryWithSameRequestIdReturnsStoredDocumentWithoutAnotherFile() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                            .file(new MockMultipartFile("file", "application.pdf", "application/pdf", fixture()))
                            .param("requestId", "same-upload-request")
                            .with(customer(1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.documentFileId").isNumber());
        }
        assertThat(count("select count(*) from document_file")).isEqualTo(1);
        assertThat(count("select count(*) from document_extraction")).isEqualTo(1);
    }

    @Test
    void rejectsForeignInactiveAndInvalidFilesWithoutPersistingMetadata() throws Exception {
        assertThrows(DomainException.class, () -> service.upload(1, 200,
                new MockMultipartFile("file", "fixture.pdf", "application/pdf", fixture())));
        assertThrows(DomainException.class, () -> service.upload(1, 300,
                new MockMultipartFile("file", "fixture.pdf", "application/pdf", fixture())));
        assertThrows(DomainException.class, () -> service.upload(1, 100,
                new MockMultipartFile("file", "fixture.jpg", "image/jpeg", fixture())));
        assertThat(count("select count(*) from document_file")).isZero();
    }

    @Test
    void customerRouteRequiresCustomerJwt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "fixture.pdf", MediaType.APPLICATION_PDF_VALUE,
                fixture());
        http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(file).param("requestId", "missing-jwt"))
                .andExpect(status().isUnauthorized());
        http.perform(multipart("/api/v1/customer/b01/work-items/{id}/documents/application", 100)
                        .file(file).param("requestId", "staff-jwt")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return jwt().jwt(token -> token.subject(Long.toString(id)).claim("role", "CUSTOMER"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private void selectLoan(long loanId, String order) throws Exception {
        http.perform(put("/api/v1/customer/b01/work-items/{id}/context", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b01-select-%d","loanId":%d,
                                 "requestedRepaymentOrder":"%s"}
                                """.formatted(loanId, loanId, order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(loanId));
    }

    private static byte[] fixture() throws Exception {
        return new ClassPathResource("b01/mock-ocr/b01-t01-application.pdf").getInputStream().readAllBytes();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static Path temporaryStorage() {
        try { return Files.createTempDirectory("b01-document-it-"); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
