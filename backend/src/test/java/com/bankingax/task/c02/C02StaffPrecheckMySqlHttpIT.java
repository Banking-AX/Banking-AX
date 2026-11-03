package com.bankingax.task.c02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full MySQL/Flyway/JWT/HTTP path for C-02 precheck, human decision and customer notification. */
@Testcontainers
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.auth.enabled=true",
        "app.auth.jwt-secret=test-only-jwt-secret-with-more-than-32-random-bytes",
        "app.auth.issuer=banking-ax-c02-staff-it",
        "app.auth.cookie-secure=false",
        "app.c02.document-port=jpa",
        "app.c02.document-provisioning-enabled=true",
        "app.c02.review-persistence-enabled=true",
        "app.c02.ocr-provider=mock",
        "app.case-port=jpa",
        "app.tool-gateway-token=test-internal-tool-token"
})
class C02StaffPrecheckMySqlHttpIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_c02_staff_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired E2eStaffAgentMockAdapter staffAgent;

    @BeforeEach
    void seedSubmittedCase() {
        jdbc.update("delete from review_item");
        jdbc.update("delete from agent_review_run");
        jdbc.update("delete from refresh_token");
        jdbc.update("delete from service_case");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(?,?,?,?,?)",
                1, "c02_customer_it", passwords.encode("customer-password"), "CUSTOMER", "Synthetic Customer");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(?,?,?,?,?)",
                101, "c02_staff_it", passwords.encode("staff-password"), "STAFF", "Synthetic Staff");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(?,?,?,?,?)",
                2, "c02_other_customer_it", passwords.encode("other-customer-password"), "CUSTOMER", "Other Synthetic Customer");
        jdbc.update("insert into workflow(id,customer_id,status) values(1,1,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(301,1,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','c02-staff-it-work')");
        String application = "{\"c02\":{\"decedent\":{\"name\":\"Synthetic Decedent\",\"birthDate\":\"1950-01-01\",\"deathDate\":\"2020-01-01\",\"depositTotal\":1000},\"heirs\":[{\"personRef\":\"h1\",\"name\":\"Synthetic Heir\",\"birthDate\":\"1980-01-01\",\"relationship\":\"child\",\"applicant\":true,\"higherOrderOrSubrogation\":false,\"visitStatus\":\"ATTENDING\",\"delegationMethod\":\"NONE\",\"delegationReceiptNo\":null}]}}";
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application,submitted_at) values(1,301,1,'INHERITANCE_DEPOSIT','SUBMITTED','PENDING',0,true,cast(? as json),utc_timestamp(6))", application);
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason,created_by_user_id) values(1,0,cast(? as json),'CREATED',1)", application);
        jdbc.update("insert into heir(id,case_id,person_ref,name,birth_date,relationship,is_applicant,visit_status,delegation_method) values(11,1,'h1','Synthetic Heir','1980-01-01','child',true,'ATTENDING','NONE')");
        jdbc.update("insert into required_document(work_item_id,document_type_code,heir_id,requirement,status,rule_version) values(301,'DECEDENT_DETAILED_FAMILY_RELATION',null,'REQUIRED','NOT_SUBMITTED','c02-g1'),(301,'DECEDENT_BASIC_CERTIFICATE',null,'REQUIRED','NOT_SUBMITTED','c02-g1'),(301,'HEIR_IDENTITY_CARD',11,'REQUIRED','NOT_SUBMITTED','c02-g1')");
        long requiredDocumentId = jdbc.queryForObject("select id from required_document where work_item_id=301 and document_type_code='DECEDENT_DETAILED_FAMILY_RELATION'", Long.class);
        jdbc.update("insert into document_file(required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,content_type,case_revision,uploaded_by,uploaded_at) values(?,1,true,'synthetic-family.pdf','pdf',128,'00000000-0000-0000-0000-000000000001.pdf','application/pdf',0,1,utc_timestamp(6))", requiredDocumentId);
        long documentFileId = jdbc.queryForObject("select id from document_file where required_document_id=?", Long.class, requiredDocumentId);
        jdbc.update("insert into document_extraction(document_file_id,case_id,revision,method,status,extracted) values(?,1,0,'MOCK','SUCCEEDED',cast(? as json))",
                documentFileId, "{\"formType\":\"SHINHAN_INHERITANCE_APPLICATION\",\"applicantName\":\"Synthetic Heir\"}");
        long pendingRequiredDocumentId = jdbc.queryForObject("select id from required_document where work_item_id=301 and document_type_code='DECEDENT_BASIC_CERTIFICATE'", Long.class);
        jdbc.update("insert into document_file(required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,content_type,case_revision,uploaded_by,uploaded_at) values(?,1,true,'synthetic-basic.pdf','pdf',128,'00000000-0000-0000-0000-000000000002.pdf','application/pdf',0,1,utc_timestamp(6))", pendingRequiredDocumentId);
        staffAgent.reset();
    }

    @Test
    void staff_precheck_human_decision_and_customer_notification_persist_with_revision_and_role_checks() throws Exception {
        String customerJwt = login("c02_customer_it", "customer-password", "CUSTOMER");
        String otherCustomerJwt = login("c02_other_customer_it", "other-customer-password", "CUSTOMER");
        String staffJwt = login("c02_staff_it", "staff-password", "STAFF");
        http.perform(get("/api/v1/auth/me").header("Authorization", bearer(staffJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("STAFF"));

        http.perform(get("/api/v1/customer/c02/1/ocr?revision=0&requestId=customer-ocr-it")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(1))
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.manualVerificationRequired").value(true))
                .andExpect(jsonPath("$.data.documents[0].documentFileId").isNumber())
                .andExpect(jsonPath("$.data.documents[0].method").value("MOCK"))
                .andExpect(jsonPath("$.data.documents[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.documents[0].extracted.applicantName").value("Synthetic Heir"))
                .andExpect(jsonPath("$.data.documents[0].manualVerificationRequired").value(true))
                .andExpect(jsonPath("$.data.documents[1].method").value("NONE"))
                .andExpect(jsonPath("$.data.documents[1].status").value("PENDING"))
                .andExpect(jsonPath("$.data.documents[1].manualVerificationRequired").value(true));
        http.perform(get("/api/v1/staff/c02/1/ocr?revision=0&requestId=staff-ocr-it")
                        .header("Authorization", bearer(staffJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[0].method").value("MOCK"))
                .andExpect(jsonPath("$.data.documents[0].extracted.applicantName").value("Synthetic Heir"));
        http.perform(get("/api/v1/customer/c02/1/ocr?revision=0&requestId=foreign-customer-ocr-it")
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/customer/c02/1/ocr?revision=1&requestId=stale-customer-ocr-it")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        http.perform(get("/api/v1/staff/c02/1/ocr?revision=0&requestId=anonymous-ocr-it"))
                .andExpect(status().isUnauthorized());

        http.perform(get("/api/v1/staff/c02/1/staff-case?requestId=staff-case-it")
                        .header("Authorization", bearer(staffJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseView.revision").value(0));

        http.perform(post("/api/v1/staff/c02/1/staff-precheck").header("Authorization", bearer(staffJwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"requestId\":\"staff-precheck-it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.agentRunStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.reviewStatus").value("WAITING_CUSTOMER"));

        assertThat(staffAgent.callCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_review_run where case_id=1 and revision=0 and status='SUCCEEDED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from review_item ri join agent_review_run ar on ar.id=ri.agent_review_run_id where ar.case_id=1 and ri.revision=0", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from service_case where id=1", Integer.class)).isEqualTo(0);
        assertThat(jdbc.queryForObject("select revision from agent_review_run where case_id=1", Integer.class)).isEqualTo(0);

        // Missing required documents require a human supplement decision; the Agent cannot decide this.
        http.perform(post("/api/v1/staff/c02/1/staff-decision").header("Authorization", bearer(customerJwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"requestId\":\"customer-decision-denied\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"Please provide the missing documents.\"}"))
                .andExpect(status().isForbidden());

        http.perform(post("/api/v1/staff/c02/1/staff-decision").header("Authorization", bearer(staffJwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"requestId\":\"stale-decision\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"Please provide the missing documents.\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from staff_review where case_id=1 and decided_at is not null", Integer.class)).isZero();

        String decision = "{\"revision\":0,\"requestId\":\"staff-decision-it\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"Please provide the missing documents.\"}";
        http.perform(post("/api/v1/staff/c02/1/staff-decision").header("Authorization", bearer(staffJwt))
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DOCUMENT_INSUFFICIENT"))
                .andExpect(jsonPath("$.data.proposedCaseStatus").value("REVIEW_REJECTED_DOCUMENT"));

        assertThat(jdbc.queryForObject("select count(*) from staff_review where case_id=1 and revision=0 and staff_id=101 and result='DOCUMENT_INSUFFICIENT' and decided_at is not null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select case_status from service_case where id=1", String.class)).isEqualTo("REVIEW_REJECTED_DOCUMENT");
        assertThat(jdbc.queryForObject("select count(*) from service_case where id=1 and result_notified_at is not null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where customer_id=1 and case_id=1 and type='C02_STAFF_REVIEW_DOCUMENT_INSUFFICIENT' and dedup_key='c02-web-1-0'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where entity_type='SERVICE_CASE' and entity_id='1' and event_type='C02_STAFF_DECIDED' and request_id='staff-decision-it'", Integer.class)).isEqualTo(1);

        // Same request replays its receipt. A distinct operation cannot make a second final decision.
        http.perform(post("/api/v1/staff/c02/1/staff-decision").header("Authorization", bearer(staffJwt))
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk());
        http.perform(post("/api/v1/staff/c02/1/staff-decision").header("Authorization", bearer(staffJwt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"requestId\":\"second-decision-it\",\"outcome\":\"DOCUMENT_INSUFFICIENT\",\"customerMessage\":\"Please provide the missing documents.\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(jdbc.queryForObject("select count(*) from staff_review where case_id=1 and decided_at is not null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where customer_id=1 and case_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where event_type='C02_STAFF_DECIDED' and entity_id='1'", Integer.class)).isEqualTo(1);

        http.perform(get("/api/v1/customer/notifications?requestId=customer-notifications-it")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].caseId").value(1))
                .andExpect(jsonPath("$.data[0].type").value("C02_STAFF_REVIEW_DOCUMENT_INSUFFICIENT"))
                .andExpect(jsonPath("$.data[0].content").value("Please provide the missing documents."));

        http.perform(post("/api/v1/staff/c02/1/staff-precheck").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"requestId\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/staff/c02/1/staff-case?requestId=customer-denied")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/staff/c02/9999/staff-case?requestId=missing-case")
                        .header("Authorization", bearer(staffJwt)))
                .andExpect(status().isNotFound());
    }

    private String login(String id, String password, String expectedRole) throws Exception {
        MvcResult result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", id, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(expectedRole)).andReturn();
        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    private static String bearer(String jwt) { return "Bearer " + jwt; }
}
