package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bank feed to staff decision over real HTTP: login, JWT, HMAC-signed feed and the running FastAPI Agent. Runs only
 * against the disposable schema and a throwaway FastAPI process; a report of the observed results lands in target/.
 */
@EnabledIfEnvironmentVariable(named = "T2_VERIFY_DB_URL", matches = ".*banking_ax_t2_verify.*")
@EnabledIfEnvironmentVariable(named = "T2_POLICY_LIVE_URL", matches = "http://127\\.0\\.0\\.1:.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyChangeLiveE2eIT {
    private static final String PASSWORD = "e2e-password";
    private static final String C02 = "INHERITANCE_DEPOSIT", B02 = "HOUSING_SAVINGS_CLOSE";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String live = System.getenv("T2_POLICY_LIVE_URL");
        registry.add("spring.datasource.url", () -> System.getenv("T2_VERIFY_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("APP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("APP_DB_PASSWORD"));
        registry.add("app.case-port", () -> "jpa");
        registry.add("app.auth.enabled", () -> "true");
        registry.add("app.auth.jwt-secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
        registry.add("app.policy-change.source-base-url", () -> live);
        registry.add("app.policy-change.source-token", () -> System.getenv("POLICY_SOURCE_TOKEN"));
        registry.add("app.policy-change.source-signing-secret", () -> System.getenv("POLICY_SOURCE_SIGNING_SECRET"));
        registry.add("app.staff-agent-base-url", () -> live);
        registry.add("app.staff-agent-token", () -> System.getenv("STAFF_AGENT_GATEWAY_TOKEN"));
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    private void seed() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                for (String table : List.of("event_log", "refresh_token", "required_document", "heir", "case_revision", "service_case", "work_item", "workflow", "user"))
                    statement.execute("truncate table `" + table + "`");
                statement.execute("set foreign_key_checks=1");
            }
            return null;
        });
        String hash = passwords.encode(PASSWORD);
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,can_pre_review) values(1,'e2e-staff',?,'STAFF','staff',true),(3,'e2e-customer',?,'CUSTOMER','customer',false)", hash, hash);
        jdbc.update("insert into workflow(id,customer_id,status) values(10,3,'RUNNING')");
        seedCase(201, "DOCUMENT", "ABSENT");
        seedCase(202, "NONE", "ATTENDING");
    }

    private void seedCase(long id, String method, String visit) {
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(?,10,?,?,'HANDED_OFF',?)", id, C02, id, "work-" + id);
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application,submitted_at) "
                + "values(?,?,3,?,'SUBMITTED',1,true,'{}',UTC_TIMESTAMP(6))", id, id, C02);
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason) values(?,1,'{}','SUBMIT')", id);
        jdbc.update("insert into heir(case_id,person_ref,name,relationship,is_applicant,visit_status,delegation_method) values(?,'H-1','n','CHILD',false,?,?)", id, visit, method);
        jdbc.update("insert into required_document(work_item_id,document_type_code,requirement,rule_version) values(?,'DECEDENT_BASIC_CERTIFICATE','REQUIRED','c02-g1')", id);
    }

    private String fingerprint() {
        return jdbc.queryForObject("""
                select md5(concat((select group_concat(concat_ws('|',id,case_status,revision,version,updated_at) order by id) from service_case),
                                  (select group_concat(concat_ws('|',id,document_type_code,requirement,rule_version,status) order by id) from required_document)))
                """, String.class);
    }

    private String url(String path) { return "http://127.0.0.1:" + port + path; }

    private String login(String loginId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange(url("/api/v1/auth/login"), HttpMethod.POST,
                new HttpEntity<>(Map.of("loginId", loginId, "password", PASSWORD), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return json.readTree(response.getBody()).path("data").path("accessToken").asText();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return json.readTree(response.getBody()).path("data");
    }

    private JsonNode get(String path, String token) throws Exception {
        return data(call(HttpMethod.GET, path + (path.contains("?") ? "&" : "?") + "requestId=" + UUID.randomUUID(), token, null));
    }

    private JsonNode post(String path, String token, Map<String, Object> body) throws Exception {
        java.util.HashMap<String, Object> request = new java.util.HashMap<>(body);
        request.put("requestId", UUID.randomUUID().toString());
        return data(call(HttpMethod.POST, path, token, request));
    }

    private void bank(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Policy-Source-Token", System.getenv("POLICY_SOURCE_TOKEN"));
        assertThat(http.getRestTemplate().exchange(System.getenv("T2_POLICY_LIVE_URL") + "/mock-bank/v1" + path, HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);
    }

    private long changeId(JsonNode overview, String task) {
        for (JsonNode row : overview.path("changes"))
            if (row.path("mappedTaskCodes").toString().contains(task) && "PENDING_REVIEW".equals(row.path("reviewStatus").asText())) return row.path("id").asLong();
        throw new AssertionError("No pending change for " + task);
    }

    @Test void aBankEventReachesStaffAndOnlyAStaffDecisionAffectsTheNotice() throws Exception {
        seed();
        String before = fingerprint();
        String staff = login("e2e-staff"), customer = login("e2e-customer");
        ObjectNode report = json.createObjectNode();

        assertThat(call(HttpMethod.GET, "/api/v1/staff/policy-changes?requestId=a", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/staff/policy-changes?requestId=b", customer, null).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/staff/policy-changes/sync", customer, Map.of("requestId", "c")).getStatusCode().value()).isEqualTo(403);

        // Scene 1: the bank publishes and Banking-AX receives.
        bank("/admin/scenarios/c02-poa-v2");
        JsonNode firstSync = post("/api/v1/staff/policy-changes/sync", staff, Map.of());
        report.set("c02Sync", firstSync);
        assertThat(firstSync.path("accepted").asInt()).isOne();

        // Scenes 2 and 3: facts, impact and the Agent's reference analysis.
        long c02 = changeId(get("/api/v1/staff/policy-changes", staff), C02);
        JsonNode detail = get("/api/v1/staff/policy-changes/" + c02, staff);
        assertThat(detail.path("view").path("effectiveState").asText()).isEqualTo("SCHEDULED");
        JsonNode candidates = detail.path("impact").get(0).path("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).path("caseId").asLong()).isEqualTo(201);
        report.set("c02Candidates", candidates);

        JsonNode analysis = post("/api/v1/staff/policy-changes/" + c02 + "/analysis", staff, Map.of());
        report.set("c02Analysis", analysis);
        assertThat(analysis.path("status").asText()).isEqualTo("SUCCEEDED");

        // Scene 4: nothing reaches the task screen before approval.
        JsonNode pending = get("/api/v1/staff/policy-changes/notices?taskCode=" + C02, staff);
        assertThat(pending.path("approved")).isEmpty();
        assertThat(pending.path("pending")).hasSize(1);
        JsonNode decision = post("/api/v1/staff/policy-changes/" + c02 + "/decision", staff, Map.of("decision", "NOTICE_APPROVED", "note", "e2e"));
        assertThat(decision.path("decision").asText()).isEqualTo("NOTICE_APPROVED");
        JsonNode approved = get("/api/v1/staff/policy-changes/notices?taskCode=" + C02, staff);
        report.set("c02NoticesAfterApproval", approved);
        assertThat(approved.path("approved")).hasSize(1);
        assertThat(fingerprint()).isEqualTo(before);

        // Retransmission of the same event changes nothing.
        bank("/admin/replay-last");
        JsonNode replay = post("/api/v1/staff/policy-changes/sync", staff, Map.of());
        report.set("replaySync", replay);
        assertThat(replay.path("duplicate").asInt()).isOne();

        // Scene 5: another task through the same API with no B-02 policy code.
        bank("/admin/scenarios/b02-info");
        assertThat(post("/api/v1/staff/policy-changes/sync", staff, Map.of()).path("accepted").asInt()).isOne();
        long b02 = changeId(get("/api/v1/staff/policy-changes", staff), B02);
        JsonNode b02Analysis = post("/api/v1/staff/policy-changes/" + b02 + "/analysis", staff, Map.of());
        report.set("b02Analysis", b02Analysis);
        post("/api/v1/staff/policy-changes/" + b02 + "/decision", staff, Map.of("decision", "NOTICE_APPROVED"));
        assertThat(get("/api/v1/staff/policy-changes/notices?taskCode=" + B02, staff).path("approved")).hasSize(1);

        // Bad data over the wire is isolated; an unreviewed scheduled version leaves the approved notice in place.
        bank("/admin/scenarios/unknown-change-type");
        bank("/admin/scenarios/bad-task-code");
        bank("/admin/scenarios/out-of-scope");
        bank("/admin/scenarios/c02-poa-v3");
        JsonNode mixed = post("/api/v1/staff/policy-changes/sync", staff, Map.of());
        report.set("mixedSync", mixed);
        assertThat(mixed.path("quarantined").asInt()).isEqualTo(2);
        assertThat(mixed.path("outOfScope").asInt()).isOne();
        assertThat(mixed.path("accepted").asInt()).isOne();
        JsonNode retired = get("/api/v1/staff/policy-changes/notices?taskCode=" + C02, staff);
        report.set("c02NoticesAfterV3", retired);
        assertThat(retired.path("approved")).hasSize(1);
        assertThat(retired.path("approved").get(0).path("sourceVersion").asText()).isEqualTo("EDU-2026.2");
        assertThat(retired.path("pending")).hasSize(1);
        assertThat(retired.path("pending").get(0).path("sourceVersion").asText()).isEqualTo("EDU-2026.3");
        assertThat(get("/api/v1/staff/policy-changes/notices?taskCode=" + C02 + "&caseId=201", staff).path("approved")).hasSize(1);
        assertThat(get("/api/v1/staff/policy-changes/notices?taskCode=" + C02 + "&caseId=202", staff).path("approved")).isEmpty();

        assertThat(fingerprint()).isEqualTo(before);
        report.put("caseAndRuleDataUnchanged", true);
        Files.writeString(Path.of("target", "policy-e2e-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
