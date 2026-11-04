package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
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
class B01LoanListMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_loan_list_it")
            .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("app.case-port", () -> "jpa");
        properties.add("app.auth.enabled", () -> "true");
        properties.add("app.auth.jwt-secret",
                () -> "b01-loan-list-test-secret-material-that-is-longer-than-sixty-four-characters");
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values "
                + "(1,'b01-list-one','x','CUSTOMER','one'),"
                + "(2,'b01-list-two','x','CUSTOMER','two'),"
                + "(3,'b01-list-empty','x','CUSTOMER','empty')");
        jdbc.update("insert into workflow(id,customer_id,status) values "
                + "(10,1,'RUNNING'),(20,2,'RUNNING'),(30,1,'RUNNING'),(40,1,'RUNNING'),(50,3,'RUNNING')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values "
                + "(100,10,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-100'),"
                + "(200,20,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-200'),"
                + "(300,30,'LOAN_INQUIRY',1,'ACTIVE','b01-list-300'),"
                + "(400,40,'REPAYMENT_ORDER_CHANGE',1,'ON_HOLD','b01-list-400'),"
                + "(500,50,'REPAYMENT_ORDER_CHANGE',1,'ACTIVE','b01-list-500')");
        jdbc.update("""
                insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,overdue_days,
                  acceleration_declared,acceleration_date,repayment_order)
                values (501,1,'210-123-557731','synthetic one',30000000,26500000,62,
                          true,'2026-08-31','INTEREST_FIRST'),
                       (502,1,'220-456-114820','synthetic two',10000000,9000000,0,
                          false,null,'PRINCIPAL_FIRST'),
                       (601,2,'999-777-123456','foreign synthetic',1000,900,1,
                          true,'2026-08-01','INTEREST_FIRST')
                """);
    }

    @Test
    void returnsOnlyAuthenticatedCustomersLoansInIdOrderWithMaskedNumbers() throws Exception {
        String response = http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100)
                        .with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskCode").value("REPAYMENT_ORDER_CHANGE"))
                .andExpect(jsonPath("$.workItemId").value(100))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].loanId").value(501))
                .andExpect(jsonPath("$.data[0].maskedAccountNumber").value("210-***-557731"))
                .andExpect(jsonPath("$.data[0].loanName").value("synthetic one"))
                .andExpect(jsonPath("$.data[0].balance").value(26500000))
                .andExpect(jsonPath("$.data[0].overdueDays").value(62))
                .andExpect(jsonPath("$.data[0].accelerationDeclared").value(true))
                .andExpect(jsonPath("$.data[0].accelerationDate").value("2026-08-31"))
                .andExpect(jsonPath("$.data[0].currentRepaymentOrder").value("INTEREST_FIRST"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("210-123-557731")
                .doesNotContain("999-777-123456")
                .doesNotContain("foreign synthetic");
    }

    @Test
    void rejectsForeignWrongTaskMissingAndInactiveWorkItemsWithoutRevealingOwnership() throws Exception {
        for (long workItemId : new long[]{200, 300, 400, 999}) {
            http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", workItemId).with(customer(1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
        }
    }

    @Test
    void returnsEmptyListWhenOwnedCustomerHasNoLoans() throws Exception {
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 500).with(customer(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void requiresCustomerJwt() throws Exception {
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/customer/b01/work-items/{id}/loans", 100)
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesAndRestoresSelectionThroughJwtContextApi() throws Exception {
        http.perform(put("/api/v1/customer/b01/work-items/{id}/context", 100)
                        .with(customer(1)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"context-1","loanId":501,
                                 "requestedRepaymentOrder":"PRINCIPAL_FIRST"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.requestedRepaymentOrder").value("PRINCIPAL_FIRST"))
                .andExpect(jsonPath("$.data.currentStep").value("APPLICATION_UPLOAD"));

        http.perform(get("/api/v1/customer/b01/work-items/{id}/context", 100).with(customer(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedLoanId").value(501))
                .andExpect(jsonPath("$.data.applicationDocument").doesNotExist());

        http.perform(get("/api/v1/customer/b01/work-items/{id}/context", 100).with(customer(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("WORK_ITEM_NOT_AVAILABLE"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(long id) {
        return jwt().jwt(token -> token.subject(Long.toString(id)).claim("role", "CUSTOMER"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
