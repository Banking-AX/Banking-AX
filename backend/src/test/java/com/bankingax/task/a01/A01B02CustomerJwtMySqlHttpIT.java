package com.bankingax.task.a01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** JWT-to-MySQL vertical path for T2 A-01 and B-02 customer APIs. */
@Testcontainers
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.auth.enabled=true",
        "app.auth.jwt-secret=test-only-jwt-secret-with-more-than-32-random-bytes",
        "app.auth.issuer=banking-ax-a01-b02-it",
        "app.auth.cookie-secure=false",
        "app.case-port=jpa",
        "app.c02.review-persistence-enabled=true",
        "app.a01.transaction-query-port=jdbc",
        "app.b02.account-port=jdbc",
        "app.reservation.enabled=false",
        "app.tool-gateway-token=test-internal-tool-token"
})
class A01B02CustomerJwtMySqlHttpIT {
    private static final long CUSTOMER_ID = 7101;
    private static final long OTHER_CUSTOMER_ID = 7102;
    private static final long A01_WORK_ITEM_ID = 7111;
    private static final long A01_FOREIGN_ACCOUNT_WORK_ITEM_ID = 7112;
    private static final long B02_WORK_ITEM_ID = 7121;
    private static final long C02_WORK_ITEM_ID = 7171;
    private static final long OTHER_CUSTOMER_WORK_ITEM_ID = 7172;
    private static final long C02_CASE_ID = 7173;
    private static final long C02_DECEDENT_ID = 7174;
    private static final long A01_ACCOUNT_ID = 7131;
    private static final long FOREIGN_A01_ACCOUNT_ID = 7132;
    private static final long B02_ACCOUNT_ID = 7141;
    private static final long FOREIGN_B02_ACCOUNT_ID = 7142;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_a01_b02_http_it").withUsername("test").withPassword("test");

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

    @BeforeEach
    void seedSyntheticCustomersAndWorkItems() {
        jdbc.update("delete from task_result where work_item_id in (?,?,?)",
                A01_WORK_ITEM_ID, A01_FOREIGN_ACCOUNT_WORK_ITEM_ID, B02_WORK_ITEM_ID);
        jdbc.update("delete from consent_record where work_item_id=?", B02_WORK_ITEM_ID);
        jdbc.update("delete from event_log where request_id like 't2-a01-b02-it-%'");
        jdbc.update("delete from account_transaction where account_id in (?,?)", A01_ACCOUNT_ID, FOREIGN_A01_ACCOUNT_ID);
        jdbc.update("delete from deposit_account where id in (?,?)", A01_ACCOUNT_ID, FOREIGN_A01_ACCOUNT_ID);
        jdbc.update("delete from housing_savings_account where id in (?,?)", B02_ACCOUNT_ID, FOREIGN_B02_ACCOUNT_ID);
        jdbc.update("delete from case_revision where case_id=?", C02_CASE_ID);
        jdbc.update("delete from service_case where id=?", C02_CASE_ID);
        jdbc.update("delete from decedent where id=?", C02_DECEDENT_ID);
        jdbc.update("delete from work_item where id in (?,?,?,?,?)",
                A01_WORK_ITEM_ID, A01_FOREIGN_ACCOUNT_WORK_ITEM_ID, B02_WORK_ITEM_ID,
                C02_WORK_ITEM_ID, OTHER_CUSTOMER_WORK_ITEM_ID);
        jdbc.update("delete from workflow where id in (?,?,?,?,?)", 7151, 7152, 7153, 7154, 7155);
        jdbc.update("delete from refresh_token where user_id in (?,?)", CUSTOMER_ID, OTHER_CUSTOMER_ID);
        jdbc.update("delete from `user` where id in (?,?)", CUSTOMER_ID, OTHER_CUSTOMER_ID);

        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                CUSTOMER_ID, "t2_a01_b02_customer", passwords.encode("customer-it-password"), "CUSTOMER", "Synthetic Customer");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values(?,?,?,?,?,true)",
                OTHER_CUSTOMER_ID, "t2_a01_b02_other", passwords.encode("other-it-password"), "CUSTOMER", "Other Synthetic Customer");

        jdbc.update("insert into workflow(id,customer_id,status) values"
                        + "(?,?,'RUNNING'),(?,?,'RUNNING'),(?,?,'RUNNING'),(?,?,'RUNNING'),(?,?,'RUNNING')",
                7151, CUSTOMER_ID, 7152, CUSTOMER_ID, 7153, CUSTOMER_ID,
                7154, CUSTOMER_ID, 7155, OTHER_CUSTOMER_ID);
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values"
                        + "(?,?, 'TRANSACTION_SEARCH',1,'ACTIVE','t2-a01-b02-it-a01'),"
                        + "(?,?, 'TRANSACTION_SEARCH',1,'ACTIVE','t2-a01-b02-it-a01-foreign-account'),"
                        + "(?,?, 'HOUSING_SAVINGS_CLOSE',1,'ACTIVE','t2-a01-b02-it-b02'),"
                        + "(?,?, 'INHERITANCE_DEPOSIT',1,'ACTIVE','t2-a01-b02-it-c02'),"
                        + "(?,?, 'TRANSACTION_SEARCH',1,'ACTIVE','t2-a01-b02-it-other-customer')",
                A01_WORK_ITEM_ID, 7151, A01_FOREIGN_ACCOUNT_WORK_ITEM_ID, 7152, B02_WORK_ITEM_ID, 7153,
                C02_WORK_ITEM_ID, 7154, OTHER_CUSTOMER_WORK_ITEM_ID, 7155);
        jdbc.update("insert into decedent(id,name,death_date,deposit_total) values(?,?,'2026-01-01',0)",
                C02_DECEDENT_ID, "Synthetic Decedent");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,"
                        + "decedent_id,visit_required,application) values(?,?,?,'INHERITANCE_DEPOSIT','DRAFT','PENDING',0,?,true,cast(? as json))",
                C02_CASE_ID, C02_WORK_ITEM_ID, CUSTOMER_ID, C02_DECEDENT_ID,
                "{\"c02\":{\"decedent\":{\"name\":\"Synthetic Decedent\",\"birthDate\":null,\"deathDate\":\"2026-01-01\",\"depositTotal\":0},\"heirs\":[]}}");
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,heir_snapshot,document_manifest,change_reason) "
                        + "values(?,0,cast(? as json),cast(? as json),cast(? as json),'CREATED')",
                C02_CASE_ID, "{\"c02\":{\"decedent\":{\"name\":\"Synthetic Decedent\",\"birthDate\":null,\"deathDate\":\"2026-01-01\",\"depositTotal\":0},\"heirs\":[]}}",
                "[]", "[]");

        jdbc.update("insert into deposit_account(id,customer_id,account_no,account_name,balance,is_active) values"
                        + "(?,?,?,'Synthetic checking',1250.00,true),(?,?,?,'Other synthetic checking',90.00,true)",
                A01_ACCOUNT_ID, CUSTOMER_ID, "7100000000131", FOREIGN_A01_ACCOUNT_ID, OTHER_CUSTOMER_ID, "7100000000132");
        jdbc.update("insert into account_transaction(id,account_id,tx_at,direction,amount,counterparty,memo) values"
                        + "(?,?,'2026-10-03 09:30:00.000000','IN',500.00,'Synthetic Payroll','Salary'),"
                        + "(?,?,'2026-10-04 11:15:00.000000','OUT',75.50,'Synthetic Market','Groceries'),"
                        + "(?,?, '2026-10-03 09:30:00.000000','IN',999.00,'Other Customer','Isolated')",
                7161, A01_ACCOUNT_ID, 7162, A01_ACCOUNT_ID, 7163, FOREIGN_A01_ACCOUNT_ID);
        jdbc.update("insert into housing_savings_account(id,customer_id,account_no,joined_at,paid_principal,status,subscription_won,converted,version) values"
                        + "(?,?,?,'2023-01-01',250000.00,'ACTIVE',false,false,0),"
                        + "(?,?,?,'2023-01-01',100000.00,'ACTIVE',false,false,0)",
                B02_ACCOUNT_ID, CUSTOMER_ID, "7200000000141", FOREIGN_B02_ACCOUNT_ID, OTHER_CUSTOMER_ID, "7200000000142");
    }

    @Test
    void actual_customer_jwt_runs_a01_and_b02_through_workflow_and_mysql_with_replay_and_ownership_checks() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=true", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("select max(cast(version as unsigned)) from flyway_schema_history where success=true", Long.class)).isEqualTo(11L);
        String customerJwt = login("t2_a01_b02_customer", "customer-it-password", CUSTOMER_ID);
        String otherCustomerJwt = login("t2_a01_b02_other", "other-it-password", OTHER_CUSTOMER_ID);

        http.perform(get("/api/v1/auth/me").header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(CUSTOMER_ID))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
        http.perform(post("/api/v1/customer/a01/transaction-search").contentType(MediaType.APPLICATION_JSON)
                        .content(a01Request("t2-a01-b02-it-a01-query", A01_WORK_ITEM_ID, A01_ACCOUNT_ID))
                )
                .andExpect(status().isUnauthorized());

        MvcResult a01 = http.perform(post("/api/v1/customer/a01/transaction-search")
                        .contentType(MediaType.APPLICATION_JSON).content(a01Request(
                                "t2-a01-b02-it-a01-query", A01_WORK_ITEM_ID, A01_ACCOUNT_ID))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("TRANSACTION_SEARCH"))
                .andExpect(jsonPath("$.workItemId").value(A01_WORK_ITEM_ID))
                .andExpect(jsonPath("$.data.accountId").value(A01_ACCOUNT_ID))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.transactions[0].amount").value(75.5))
                .andReturn();
        JsonNode a01Response = json.readTree(a01.getResponse().getContentAsString()).path("data");
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, A01_WORK_ITEM_ID)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select status from workflow where id=7151", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=? and result_type='TRANSACTION_SEARCH'", Integer.class, A01_WORK_ITEM_ID)).isEqualTo(1);
        assertThat(json.readTree(jdbc.queryForObject("select detail from task_result where work_item_id=?", String.class, A01_WORK_ITEM_ID))
                .path("response").path("totalElements").asLong()).isEqualTo(2);

        http.perform(get("/api/v1/customer/a01/work-items/" + A01_WORK_ITEM_ID + "/result")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workItemId").value(A01_WORK_ITEM_ID))
                .andExpect(jsonPath("$.data.accountId").value(A01_ACCOUNT_ID))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        http.perform(get("/api/v1/customer/a01/work-items/" + A01_WORK_ITEM_ID + "/result")
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        http.perform(get("/api/v1/customer/a01/work-items/" + A01_WORK_ITEM_ID + "/result"))
                .andExpect(status().isUnauthorized());

        MvcResult a01Replay = http.perform(post("/api/v1/customer/a01/transaction-search")
                        .contentType(MediaType.APPLICATION_JSON).content(a01Request(
                                "t2-a01-b02-it-a01-query", A01_WORK_ITEM_ID, A01_ACCOUNT_ID))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(a01Replay.getResponse().getContentAsString()).path("data"))
                .isEqualTo(a01Response);
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=?", Integer.class, A01_WORK_ITEM_ID)).isEqualTo(1);

        http.perform(post("/api/v1/customer/a01/transaction-search").contentType(MediaType.APPLICATION_JSON)
                        .content(a01Request("t2-a01-b02-it-a01-foreign-account", A01_FOREIGN_ACCOUNT_WORK_ITEM_ID, FOREIGN_A01_ACCOUNT_ID))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, A01_FOREIGN_ACCOUNT_WORK_ITEM_ID)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=?", Integer.class, A01_FOREIGN_ACCOUNT_WORK_ITEM_ID)).isZero();
        http.perform(post("/api/v1/customer/a01/transaction-search").contentType(MediaType.APPLICATION_JSON)
                        .content(a01Request("t2-a01-b02-it-a01-foreign-work", A01_WORK_ITEM_ID, A01_ACCOUNT_ID))
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));

        http.perform(post("/api/v1/customer/b02/prepare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t2-a01-b02-it-prepare\",\"account\":" + FOREIGN_B02_ACCOUNT_ID + ",\"waive\":true,\"taxed\":false}")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(otpRequest("t2-a01-b02-it-foreign-workitem", B02_WORK_ITEM_ID, true))
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        http.perform(get("/api/v1/customer/b02/work-items/" + B02_WORK_ITEM_ID + "/result"))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/customer/b02/prepare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t2-a01-b02-it-prepare\",\"account\":" + B02_ACCOUNT_ID + ",\"waive\":true,\"taxed\":false}")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rule").value("B2-R3"));

        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(otpRequest("t2-a01-b02-it-no-consent", B02_WORK_ITEM_ID, false))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CONSENT_OR_SIGNATURE_REQUIRED"));
        assertThat(jdbc.queryForObject("select count(*) from consent_record where work_item_id=?", Integer.class, B02_WORK_ITEM_ID)).isZero();

        MvcResult issue = http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(otpRequest("t2-a01-b02-it-otp", B02_WORK_ITEM_ID, true))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workItemId").value(B02_WORK_ITEM_ID)).andReturn();
        String challenge = json.readTree(issue.getResponse().getContentAsString()).at("/data/challengeId").asText();
        assertThat(challenge).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from consent_record where work_item_id=? and agreed=true", Integer.class, B02_WORK_ITEM_ID)).isEqualTo(1);

        http.perform(post("/api/v1/customer/b02/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t2-a01-b02-it-wrong-otp\",\"challenge\":\"" + challenge + "\",\"otp\":\"123456\"}")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("OTP_INVALID"));

        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest("t2-a01-b02-it-before-verify", B02_WORK_ITEM_ID, "missing-binding"))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VERIFICATION_BINDING_INVALID"));
        assertB02StillActive();

        MvcResult verified = http.perform(post("/api/v1/customer/b02/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t2-a01-b02-it-verify\",\"challenge\":\"" + challenge + "\",\"otp\":\"000000\"}")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andReturn();
        String binding = json.readTree(verified.getResponse().getContentAsString()).at("/data/bindingId").asText();
        assertThat(binding).isNotBlank();
        JsonNode bindingResponse = json.readTree(verified.getResponse().getContentAsString()).path("data");
        assertThat(bindingResponse.path("workItemId").asLong()).isEqualTo(B02_WORK_ITEM_ID);
        assertThat(bindingResponse.path("workItemId").asLong()).isNotEqualTo(B02_ACCOUNT_ID);
        assertThat(bindingResponse.path("action").asText()).isEqualTo("B02_EXECUTE");
        assertThat(bindingResponse.path("requestVersion").asInt()).isZero();

        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest("t2-a01-b02-it-execute", B02_WORK_ITEM_ID, binding))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("HOUSING_SAVINGS_CLOSE"))
                .andExpect(jsonPath("$.workItemId").value(B02_WORK_ITEM_ID))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        assertThat(jdbc.queryForObject("select status from housing_savings_account where id=?", String.class, B02_ACCOUNT_ID)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select version from housing_savings_account where id=?", Integer.class, B02_ACCOUNT_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select paid_principal from housing_savings_account where id=?", BigDecimal.class, B02_ACCOUNT_ID))
                .isEqualByComparingTo("250000.00");
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, B02_WORK_ITEM_ID)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select status from workflow where id=7153", String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=? and result_type='HOUSING_SAVINGS_CLOSE'", Integer.class, B02_WORK_ITEM_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select decision_code from task_result where work_item_id=?", String.class, B02_WORK_ITEM_ID)).isEqualTo("B2-R3");
        assertThat(jdbc.queryForObject("select rule_version from task_result where work_item_id=?", String.class, B02_WORK_ITEM_ID)).isEqualTo("1.7.1");
        JsonNode before = json.readTree(jdbc.queryForObject("select before_value from task_result where work_item_id=?", String.class, B02_WORK_ITEM_ID));
        JsonNode after = json.readTree(jdbc.queryForObject("select after_value from task_result where work_item_id=?", String.class, B02_WORK_ITEM_ID));
        assertThat(before.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(before.path("version").asInt()).isZero();
        assertThat(after.path("status").asText()).isEqualTo("CLOSED");
        assertThat(after.path("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where request_id='t2-a01-b02-it-execute' and event_type='B02_HOUSING_SAVINGS_CLOSE'", Integer.class)).isEqualTo(1);

        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest("t2-a01-b02-it-execute", B02_WORK_ITEM_ID, binding))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rate").value("REPLAY"));
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=?", Integer.class, B02_WORK_ITEM_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from event_log where request_id='t2-a01-b02-it-execute'", Integer.class)).isEqualTo(1);

        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"t2-a01-b02-it-execute\",\"version\":0,\"waive\":false,\"taxed\":false,\"binding\":\""
                                + binding + "\",\"workItemId\":" + B02_WORK_ITEM_ID + "}")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST_CONFLICT"));
        http.perform(post("/api/v1/customer/b02/" + B02_ACCOUNT_ID + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content(executeRequest("t2-a01-b02-it-different-request", B02_WORK_ITEM_ID, binding))
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST_CONFLICT"));
        http.perform(get("/api/v1/customer/b02/work-items/" + B02_WORK_ITEM_ID + "/result")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workItemId").value(B02_WORK_ITEM_ID))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.afterValue.status").value("CLOSED"));
        http.perform(get("/api/v1/customer/b02/work-items/" + B02_WORK_ITEM_ID + "/result")
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from consent_record where work_item_id=? and agreed=true", Integer.class, B02_WORK_ITEM_ID)).isEqualTo(1);

        http.perform(get("/api/v1/customer/task-history"))
                .andExpect(status().isUnauthorized());
        MvcResult customerHistory = http.perform(get("/api/v1/customer/task-history")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("CUSTOMER_TASK_HISTORY"))
                .andReturn();
        JsonNode customerItems = json.readTree(customerHistory.getResponse().getContentAsString()).path("data");
        assertThat(customerItems.size()).isEqualTo(4);
        JsonNode a01History = findHistoryItem(customerItems, A01_WORK_ITEM_ID);
        assertThat(a01History.path("taskCode").asText()).isEqualTo("TRANSACTION_SEARCH");
        assertThat(a01History.path("workItemStatus").asText()).isEqualTo("DONE");
        assertThat(a01History.path("workflowStatus").asText()).isEqualTo("COMPLETED");
        assertThat(a01History.path("resultAvailable").asBoolean()).isTrue();
        JsonNode b02History = findHistoryItem(customerItems, B02_WORK_ITEM_ID);
        assertThat(b02History.path("taskCode").asText()).isEqualTo("HOUSING_SAVINGS_CLOSE");
        assertThat(b02History.path("workItemStatus").asText()).isEqualTo("DONE");
        assertThat(b02History.path("workflowStatus").asText()).isEqualTo("COMPLETED");
        assertThat(b02History.path("resultAvailable").asBoolean()).isTrue();
        JsonNode c02History = findHistoryItem(customerItems, C02_WORK_ITEM_ID);
        assertThat(c02History.path("taskCode").asText()).isEqualTo("INHERITANCE_DEPOSIT");
        assertThat(c02History.path("workItemStatus").asText()).isEqualTo("ACTIVE");
        assertThat(c02History.path("caseId").asLong()).isEqualTo(C02_CASE_ID);
        assertThat(c02History.path("caseStatus").asText()).isEqualTo("DRAFT");
        assertThat(c02History.path("resultAvailable").asBoolean()).isTrue();
        http.perform(get("/api/v1/customer/c02/" + C02_CASE_ID + "/snapshot?requestId=t2-history-c02-snapshot")
                        .header("Authorization", bearer(customerJwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseId").value(C02_CASE_ID))
                .andExpect(jsonPath("$.data.workItemId").value(C02_WORK_ITEM_ID))
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.caseStatus").value("DRAFT"));
        http.perform(get("/api/v1/customer/c02/" + C02_CASE_ID + "/snapshot?requestId=t2-history-c02-foreign")
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isForbidden());
        JsonNode otherHistory = json.readTree(http.perform(get("/api/v1/customer/task-history")
                        .header("Authorization", bearer(otherCustomerJwt)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(otherHistory.size()).isEqualTo(1);
        assertThat(otherHistory.get(0).path("workItemId").asLong()).isEqualTo(OTHER_CUSTOMER_WORK_ITEM_ID);
        assertThat(otherHistory.findValuesAsText("workItemId")).doesNotContain(
                Long.toString(A01_WORK_ITEM_ID), Long.toString(B02_WORK_ITEM_ID), Long.toString(C02_WORK_ITEM_ID));
    }

    private JsonNode findHistoryItem(JsonNode items, long workItemId) {
        for (JsonNode item : items) if (item.path("workItemId").asLong() == workItemId) return item;
        throw new AssertionError("Task history did not contain work item " + workItemId);
    }

    private void assertB02StillActive() {
        assertThat(jdbc.queryForObject("select status from housing_savings_account where id=?", String.class, B02_ACCOUNT_ID)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from work_item where id=?", String.class, B02_WORK_ITEM_ID)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from task_result where work_item_id=?", Integer.class, B02_WORK_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from event_log where entity_type='HOUSING_SAVINGS_ACCOUNT' and entity_id=?", Integer.class, String.valueOf(B02_ACCOUNT_ID))).isZero();
    }

    private String login(String loginId, String password, long expectedCustomerId) throws Exception {
        MvcResult result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginId", loginId, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(expectedCustomerId))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER")).andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).at("/data/accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String a01Request(String requestId, long workItem, long account) throws Exception {
        return json.writeValueAsString(new A01CustomerBffController.Request(requestId, workItem, account,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, 0, 20,
                null, null, null, null));
    }

    private String otpRequest(String requestId, long workItem, boolean consent) {
        return "{\"requestId\":\"" + requestId + "\",\"version\":0,\"consent\":" + consent
                + ",\"signed\":true,\"phone\":\"01000000000\",\"workItemId\":" + workItem + "}";
    }

    private String executeRequest(String requestId, long workItem, String binding) {
        return "{\"requestId\":\"" + requestId + "\",\"version\":0,\"waive\":true,\"taxed\":false,\"binding\":\""
                + binding + "\",\"workItemId\":" + workItem + "}";
    }

    private static String bearer(String jwt) { return "Bearer " + jwt; }
}
