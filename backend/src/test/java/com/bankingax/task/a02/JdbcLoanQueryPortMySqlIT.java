package com.bankingax.task.a02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.LoanInquiryToolRequest;
import com.bankingax.core.tool.ToolActor;
import com.bankingax.core.tool.ToolGatewayService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.case-port=jpa", "app.a02.loan-query-port=jdbc", "app.tool-gateway-token=test-token"})
class JdbcLoanQueryPortMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_a02_it").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired LoanQueryPort loans;
    @Autowired ToolGatewayService gateway;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM task_result");
        jdbc.update("DELETE FROM loan");
        jdbc.update("DELETE FROM work_item");
        jdbc.update("DELETE FROM workflow");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES "
                + "(1,'a02-customer-1','x','CUSTOMER','one'),(2,'a02-customer-2','x','CUSTOMER','two')");
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES "
                + "(10,1,'RUNNING'),(20,2,'RUNNING'),(30,1,'RUNNING')");
        jdbc.update("""
                INSERT INTO work_item(id,workflow_id,task_code,sequence_no,status,request_id)
                VALUES
                  (101,10,'LOAN_INQUIRY',1,'ACTIVE','a02-active'),
                  (102,20,'LOAN_INQUIRY',1,'ACTIVE','a02-foreign'),
                  (103,30,'TRANSACTION_SEARCH',1,'ACTIVE','a02-other-task'),
                  (104,10,'LOAN_INQUIRY',3,'ON_HOLD','a02-held'),
                  (105,10,'LOAN_INQUIRY',4,'DONE','a02-done')
                """);
        jdbc.update("""
                INSERT INTO loan
                  (id,customer_id,loan_no,loan_name,principal_amount,balance,interest_rate,
                   overdue_days,acceleration_declared,repayment_order)
                VALUES
                  (1,1,'210-123-557731','normal',12000000,9000000,5.2000,0,false,'INTEREST_FIRST'),
                  (2,1,'220-456-998877','overdue',24000000,20000000,6.1000,45,true,'PRINCIPAL_FIRST'),
                  (3,1,'SHORT9','excluded',5000000,4800000,7.0000,0,false,'INTEREST_FIRST'),
                  (4,2,'230-999-111122','foreign',1000000,500000,4.0000,0,false,'INTEREST_FIRST')
                """);
    }

    @Test
    void listsOnlyOwnedLoansInIdOrderAndMapsEveryContractField() {
        var result = loans.findOwnedLoans(1, null);

        assertEquals(3, result.size());
        assertEquals(java.util.List.of(1L, 2L, 3L), result.stream().map(LoanSummary::loanId).toList());
        LoanSummary overdue = result.get(1);
        assertEquals("220-***-998877", overdue.loanNoMasked());
        assertEquals("overdue", overdue.loanName());
        assertEquals(new BigDecimal("20000000.00"), overdue.balance());
        assertEquals(new BigDecimal("6.1000"), overdue.interestRate());
        assertEquals(45, overdue.overdueDays());
        assertTrue(overdue.accelerationDeclared());
        assertEquals("PRINCIPAL_FIRST", overdue.repaymentOrder());
        assertFalse(result.stream().map(LoanSummary::loanNoMasked).anyMatch(value -> value.equals("210-123-557731")));
        assertEquals("SH-***-ORT9", result.get(2).loanNoMasked());
    }

    @Test
    void returnsOneOwnedLoanForSpecificId() {
        var result = loans.findOwnedLoans(1, 1L);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).loanId());
        assertEquals("210-***-557731", result.get(0).loanNoMasked());
    }

    @Test
    void returnsEmptyForMissingOrForeignLoan() {
        assertTrue(loans.findOwnedLoans(1, 999L).isEmpty());
        assertTrue(loans.findOwnedLoans(1, 4L).isEmpty());
        assertEquals(java.util.List.of(4L), loans.findOwnedLoans(2, null).stream().map(LoanSummary::loanId).toList());
    }

    @Test
    void preservesTheExistingToolGatewayRequestAndResponseContract() {
        var response = gateway.loanInquiry(
                new ToolActor(1, ActorRole.CUSTOMER),
                new LoanInquiryToolRequest("a02-active", 101L, 1, 2L));

        assertEquals(1, response.loans().size());
        assertEquals(2L, response.loans().get(0).loanId());
        assertEquals("220-***-998877", response.loans().get(0).loanNoMasked());
    }

    @Test
    void rejectsMissingForeignWrongTaskAndInactiveWorkItems() {
        assertUnavailable(null, 1);
        assertUnavailable(999L, 1);
        assertUnavailable(102L, 1);
        assertUnavailable(103L, 1);
        assertUnavailable(104L, 1);
        assertUnavailable(105L, 1);
    }

    private void assertUnavailable(Long workItemId, long customerId) {
        DomainException error = assertThrows(DomainException.class, () -> gateway.loanInquiry(
                new ToolActor(customerId, ActorRole.CUSTOMER),
                new LoanInquiryToolRequest("a02-invalid-work-item", workItemId, customerId, null)));
        assertEquals("WORK_ITEM_NOT_AVAILABLE", error.code());
    }
}
