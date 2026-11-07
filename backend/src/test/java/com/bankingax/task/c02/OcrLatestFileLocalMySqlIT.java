package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A later upload raises the Case revision. OCR results must follow the latest file through that, and a file with no
 * extraction must stay without one. Never point T2_VERIFY_DB_URL at the shared banking_ax database: every test truncates.
 */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OcrLatestFileLocalMySqlIT {
    private static final long CASE = 600, POA_V1 = 39, POA_V2 = 40, ID_COPY = 41;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    OcrPersistence ocr;

    @BeforeEach void seed() {
        ocr = new OcrPersistence(jdbc, null, json);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("review_item", "agent_review_run", "document_extraction", "document_file", "required_document",
                        "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(3,'ocr','x','CUSTOMER','c')");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,3,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(32,10,'INHERITANCE_DEPOSIT',1,'ACTIVE','w')");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application) "
                + "values(?,32,3,'INHERITANCE_DEPOSIT','SUBMITTED',6,true,'{}')", CASE);
        for (int revision : List.of(0, 1, 6))
            jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values(?,?,'{}','T')", CASE, revision);
        jdbc.update("insert into required_document(id,work_item_id,document_type_code,requirement) values"
                + "(1,32,'ABSENT_HEIR_POWER_OF_ATTORNEY','REQUIRED'),(2,32,'ABSENT_HEIR_ID_COPY','REQUIRED')");
    }

    private void file(long id, long required, int version, boolean latest, int revision) {
        jdbc.update("insert into document_file(id,required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,checksum,case_revision,uploaded_by) "
                + "values(?,?,?,?,'f.pdf','pdf',10,?,repeat('a',64),?,3)", id, required, version, latest, "s" + id, revision);
    }

    private void extraction(long fileId, int revision, String status) {
        jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,extracted) values(?,?,?,'MOCK',?,'{}')", fileId, CASE, revision, status);
    }

    private void reviewItem(long fileId, int revision, String result) {
        Integer existing = jdbc.queryForObject("select count(*) from agent_review_run where case_id=? and revision=?", Integer.class, CASE, revision);
        if (existing == 0) jdbc.update("insert into agent_review_run(case_id,revision,check_type,status) values(?,?,'C02_OCR_PRECHECK','SUCCEEDED')", CASE, revision);
        Long run = jdbc.queryForObject("select id from agent_review_run where case_id=? and revision=?", Long.class, CASE, revision);
        jdbc.update("insert into review_item(case_id,revision,agent_review_run_id,source,category,result,document_file_id,message) values(?,?,?,'SPRING_RULE','C02_POA_FIELD',?,?,'m')",
                CASE, revision, run, result, fileId);
    }

    @Test void aResultSurvivesLaterUploadsThatRaiseTheCaseRevision() {
        file(POA_V1, 1, 1, true, 0);
        extraction(POA_V1, 1, "SUCCEEDED");
        reviewItem(POA_V1, 1, "AUTO_MATCHED");

        assertThat(ocr.snapshot(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("documentFileId", POA_V1).containsEntry("status", "SUCCEEDED"));
        assertThat(ocr.reviewItems(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("result", "AUTO_MATCHED"));
    }

    @Test void aFileWithNoExtractionStaysWithoutAResult() {
        file(POA_V1, 1, 1, true, 0);

        assertThat(ocr.snapshot(CASE)).isEmpty();
        assertThat(ocr.reviewItems(CASE)).isEmpty();
    }

    @Test void aReplacedFileNoLongerSpeaksForTheCase() {
        file(POA_V1, 1, 1, false, 0);
        file(POA_V2, 1, 2, true, 1);
        extraction(POA_V1, 1, "SUCCEEDED");
        reviewItem(POA_V1, 1, "AUTO_MATCHED");
        extraction(POA_V2, 6, "FAILED_NEEDS_STAFF");
        reviewItem(POA_V2, 6, "STAFF_REQUIRED");

        assertThat(ocr.snapshot(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("documentFileId", POA_V2).containsEntry("status", "FAILED_NEEDS_STAFF"));
        assertThat(ocr.reviewItems(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("result", "STAFF_REQUIRED"));
    }

    @Test void aRepeatedExtractionOfOneFileShowsOnlyTheNewest() {
        file(POA_V1, 1, 1, true, 0);
        extraction(POA_V1, 1, "FAILED_NEEDS_STAFF");
        extraction(POA_V1, 6, "SUCCEEDED");

        assertThat(ocr.snapshot(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "SUCCEEDED"));
    }

    /** Runs a CLOVA-shaped response through the real adapter and persistence; only the HTTP peer is local. */
    private List<String> uploadWith(String extraField) throws Exception {
        jdbc.update("update service_case set application=? where id=?", """
                {"c02":{"decedent":{"name":"김영준","birthDate":"1955-01-01"},"heirs":[{"name":"김민수","birthDate":"1997-04-12","visitStatus":"ATTENDING"},{"name":"김하나","birthDate":"1994-01-03","visitStatus":"ABSENT"}]}}
                """, CASE);
        String body = "{\"images\":[{\"inferResult\":\"SUCCESS\",\"matchedTemplate\":{\"id\":43868},\"fields\":["
                + "{\"name\":\"depositor_name\",\"inferText\":\"김영준\"},{\"name\":\"agent_name\",\"inferText\":\"김민수\"},"
                + "{\"name\":\"delegator_1_name\",\"inferText\":\"김하나\"},{\"name\":\"account_1_number\",\"inferText\":\"99900000000001\"},"
                + "{\"name\":\"1_생년월일\",\"inferText\":\"1955년01 월01일\"},{\"name\":\"2_생년월일\",\"inferText\":\"1997 년 04월 13일\"},"
                + "{\"name\":\"delegator_1_birth6\",\"inferText\":\"1994년01월03일\"}"
                + extraField + "]}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/infer", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var storage = new C02FileStorage(Files.createTempDirectory("ocr-it-").toString(), 1_000_000);
            var stored = storage.store(new MockMultipartFile("file", "poa.pdf", "application/pdf", C02DemoSeeder.DemoPdf.of("poa").getBytes()));
            jdbc.update("insert into document_file(id,required_document_id,version,is_latest,original_name,extension,content_type,size_bytes,storage_path,checksum,case_revision,uploaded_by) "
                    + "values(?,1,1,true,'poa.pdf','pdf','application/pdf',10,?,repeat('a',64),0,3)", POA_V1, stored.storageKey());
            var adapter = new ClovaOcrHttpAdapter("http://127.0.0.1:" + server.getAddress().getPort() + "/infer", "unused", "43868", 1, 1, json);
            new OcrPersistence(jdbc, adapter, json, storage).extract(POA_V1, CASE, 6, "ABSENT_HEIR_POWER_OF_ATTORNEY", null);
        } finally {
            server.stop(0);
        }
        assertThat(jdbc.queryForObject("select concat_ws('/',method,status,failure_reason) from document_extraction where document_file_id=?", String.class, POA_V1))
                .isEqualTo("CLOVA_TEMPLATE/SUCCEEDED");
        return jdbc.queryForList("select result from review_item where case_id=? order by id", String.class, CASE);
    }

    @Test void aTemplateWithTheDelegationPurposeFieldReachesTheFieldComparison() throws Exception {
        assertThat(uploadWith(",{\"name\":\"delegation_purpose\",\"inferText\":\"상속예금 해지에 관한 사항을 위임합니다\"}"))
                .containsExactly("TYPE_MATCHED", "AUTO_MATCHED", "AUTO_MATCHED", "AUTO_MATCHED", "STAFF_REQUIRED",
                        "AUTO_MATCHED", "MISMATCH", "AUTO_MATCHED");
        assertThat(jdbc.queryForList("select category from review_item where case_id=? and result='MISMATCH'", String.class, CASE))
                .containsExactly("C02_POA_agent.birthDate");
    }

    @Test void aTemplateWithoutTheFieldStaysUnverifiableAndComparesNothing() throws Exception {
        assertThat(uploadWith("")).containsExactly("TYPE_UNVERIFIABLE");
    }

    @Test void anEmptyPurposeFieldIsNoStructuralEvidence() throws Exception {
        assertThat(uploadWith(",{\"name\":\"delegation_purpose\",\"inferText\":\"\"}")).containsExactly("TYPE_UNVERIFIABLE");
    }

    @Test void aBlankPurposeFieldIsNoStructuralEvidence() throws Exception {
        assertThat(uploadWith(",{\"name\":\"delegation_purpose\",\"inferText\":\"   \"}")).containsExactly("TYPE_UNVERIFIABLE");
    }

    @Test void aCustomerSeesTheStatusButNeverTheExtractedValues() {
        file(POA_V1, 1, 1, true, 0);
        extraction(POA_V1, 1, "SUCCEEDED");

        assertThat(ocr.currentSnapshot(CASE, 6).documents()).singleElement().satisfies(row -> assertThat(row).containsKey("extracted"));
        assertThat(ocr.customerSnapshot(CASE, 6).documents()).singleElement().satisfies(row -> assertThat(row)
                .doesNotContainKey("extracted").containsEntry("status", "SUCCEEDED"));
    }

    @Test void otherFilesKeepTheirOwnStatus() {
        file(POA_V1, 1, 1, true, 0);
        file(ID_COPY, 2, 1, true, 1);
        extraction(ID_COPY, 6, "NOT_APPLICABLE");

        assertThat(ocr.snapshot(CASE)).singleElement().satisfies(row -> assertThat(row).containsEntry("documentFileId", ID_COPY));
    }
}
