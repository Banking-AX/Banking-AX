package com.bankingax.task.a02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class CustomerLoanBffControllerMySqlIT {
    private static final long CUSTOMER_ONE = 91001L;
    private static final long CUSTOMER_TWO = 91002L;
    private static final long CUSTOMER_EMPTY = 91003L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_customer_loans_it")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("app.case-port", () -> "jpa");
        properties.add("app.auth.enabled", () -> "true");
        properties.add("app.auth.jwt-secret",
                () -> "customer-loans-test-secret-material-that-is-longer-than-sixty-four-characters");
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from task_result where id between 91900 and 91999");
        jdbc.update("delete from loan where id between 91500 and 91699");
        jdbc.update("delete from work_item where id between 91100 and 91299");
        jdbc.update("delete from workflow where id between 91010 and 91049");
        jdbc.update("delete from `user` where id between 91001 and 91003");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values "
                + "(?, 'customer-loans-one', 'x', 'CUSTOMER', 'one'),"
                + "(?, 'customer-loans-two', 'x', 'CUSTOMER', 'two'),"
                + "(?, 'customer-loans-empty', 'x', 'CUSTOMER', 'empty')",
                CUSTOMER_ONE, CUSTOMER_TWO, CUSTOMER_EMPTY);
        jdbc.update("insert into workflow(id,customer_id,status) values "
                + "(91010,?,'COMPLETED'),(91020,?,'RUNNING'),(91030,?,'COMPLETED'),(91040,?,'COMPLETED')",
                CUSTOMER_ONE, CUSTOMER_ONE, CUSTOMER_TWO, CUSTOMER_ONE);
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(91100,91010,'REPAYMENT_ORDER_CHANGE',1,'DONE','customer-loans-done-old'),"
                + "(91101,91020,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','customer-loans-active'),"
                + "(91200,91030,'REPAYMENT_ORDER_CHANGE',1,'DONE','customer-loans-foreign'),"
                + "(91102,91040,'REPAYMENT_ORDER_CHANGE',1,'DONE','customer-loans-done-new')");
        jdbc.update("""
                insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,interest_rate,
                                 overdue_days,acceleration_declared,acceleration_date,repayment_order)
                values (91501,?,'210-123-557731','synthetic overdue',30000000,26500000,7.2000,
                        62,true,'2026-08-31','PRINCIPAL_FIRST'),
                       (91502,?,'220-456-114820',null,10000000,9000000,null,
                        0,false,null,'INTEREST_FIRST'),
                       (91601,?,'999-777-123456','foreign synthetic',1000,900,4.0000,
                        1,true,'2026-08-01','INTEREST_FIRST')
                """, CUSTOMER_ONE, CUSTOMER_ONE, CUSTOMER_TWO);
        insertResult(91900, 91100, 91501, "2026-10-01 01:02:03.000000");
        insertResult(91901, 91102, 91501, "2026-10-02 04:05:06.000000");
        insertResult(91902, 91101, 91501, "2026-10-03 07:08:09.000000");
        insertResult(91903, 91200, 91501, "2026-10-04 10:11:12.000000");
    }

    private void insertResult(long id, long workItemId, long loanId, String createdAt) {
        jdbc.update("""
                insert into task_result(id,work_item_id,result_type,decision_code,target_loan_id,rule_version,
                                        before_value,after_value,detail,created_at)
                values (?,?,'REPAYMENT_ORDER_CHANGE','ELIGIBLE',?,'1.7.1',
                        json_object('repaymentOrder','INTEREST_FIRST'),
                        json_object('repaymentOrder','PRINCIPAL_FIRST'),
                        json_object('requestId',concat('result-',?)),?)
                """, id, workItemId, loanId, id, createdAt);
    }

    @Test
    void returnsOnlyJwtCustomersLoansWithEveryDetailField() throws Exception {
        String response = http.perform(get("/api/v1/customer/loans?requestId=customer-loans-1")
                        .with(customer(CUSTOMER_ONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("customer-loans-1"))
                .andExpect(jsonPath("$.taskCode").value("LOAN_INQUIRY"))
                .andExpect(jsonPath("$.workItemId").value(nullValue()))
                .andExpect(jsonPath("$.data.asOf").isString())
                .andExpect(jsonPath("$.data.loans.length()").value(2))
                .andExpect(jsonPath("$.data.loans[0].loanId").value(91501))
                .andExpect(jsonPath("$.data.loans[0].loanNoMasked").value("210-***-557731"))
                .andExpect(jsonPath("$.data.loans[0].loanName").value("synthetic overdue"))
                .andExpect(jsonPath("$.data.loans[0].balance").value(26500000))
                .andExpect(jsonPath("$.data.loans[0].interestRate").value(7.2))
                .andExpect(jsonPath("$.data.loans[0].overdueDays").value(62))
                .andExpect(jsonPath("$.data.loans[0].accelerationDeclared").value(true))
                .andExpect(jsonPath("$.data.loans[0].accelerationDate").value("2026-08-31"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrderSteps[0]").value("COST"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrderSteps[1]").value("PRINCIPAL"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrderSteps[2]").value("INTEREST"))
                .andExpect(jsonPath("$.data.loans[0].repaymentOrderChangedAt")
                        .value("2026-10-02T04:05:06Z"))
                .andExpect(jsonPath("$.data.loans[1].loanName").value(nullValue()))
                .andExpect(jsonPath("$.data.loans[1].interestRate").value(nullValue()))
                .andExpect(jsonPath("$.data.loans[1].accelerationDate").value(nullValue()))
                .andExpect(jsonPath("$.data.loans[1].repaymentOrderSteps[1]").value("INTEREST"))
                .andExpect(jsonPath("$.data.loans[1].repaymentOrderChangedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("210-123-557731")
                .doesNotContain("999-777-123456")
                .doesNotContain("foreign synthetic");
        assertThat(jdbc.queryForMap("select balance,repayment_order,version from loan where id=91501"))
                .isEqualTo(Map.of("balance", new java.math.BigDecimal("26500000.00"),
                        "repayment_order", "PRINCIPAL_FIRST", "version", 0));
    }

    @Test
    void returnsEmptyListForOwnedCustomerWithoutLoans() throws Exception {
        http.perform(get("/api/v1/customer/loans?requestId=customer-loans-empty")
                        .with(customer(CUSTOMER_EMPTY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").isString())
                .andExpect(jsonPath("$.data.loans.length()").value(0));
    }

    @Test
    void requiresCustomerJwtAndValidRequestId() throws Exception {
        http.perform(get("/api/v1/customer/loans?requestId=anonymous"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/customer/loans?requestId=staff")
                        .with(jwt().jwt(token -> token.subject("92001").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/customer/loans?requestId=admin")
                        .with(jwt().jwt(token -> token.subject("92002").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/customer/loans?requestId=   ").with(customer(CUSTOMER_ONE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        http.perform(get("/api/v1/customer/loans").with(customer(CUSTOMER_ONE)))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return jwt().jwt(token -> token.subject(Long.toString(id)).claim("role", "CUSTOMER"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
