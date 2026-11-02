package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;

import com.bankingax.task.a02.LoanQueryPort;
import java.util.Map;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.case-port=jpa", "app.a02.loan-query-port=jdbc"})
class B01LoanSeedMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_b01_seed_it")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired LoanQueryPort loans;

    @BeforeEach
    void seedExistingDemoLoans() {
        jdbc.update("DELETE FROM loan");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("""
                INSERT INTO `user`(id, login_id, password_hash, `role`, name)
                VALUES (1, 'customer_demo', 'not-a-login-hash', 'CUSTOMER', '김민수')
                """);
        jdbc.update("""
                INSERT INTO loan
                  (id, customer_id, loan_no, loan_name, principal_amount, balance,
                   interest_rate, maturity_date, overdue_days, acceleration_declared,
                   acceleration_date, repayment_order, outsourced, agreement_loan,
                   has_third_party_provider)
                VALUES
                  (1, 1, 'MOCK-LN-001', '가상 일반신용대출', 12000000, 9000000,
                   5.2000, '2028-08-31', 0, FALSE, NULL, 'INTEREST_FIRST', FALSE, FALSE, FALSE),
                  (2, 1, 'MOCK-LN-002', '가상 생활안정 대출', 24000000, 20000000,
                   6.1000, '2029-05-31', 45, FALSE, NULL, 'INTEREST_FIRST', FALSE, FALSE, FALSE),
                  (3, 1, 'MOCK-LN-003', '가상 제외조건 검증 대출', 5000000, 4800000,
                   7.0000, '2027-12-31', 0, FALSE, NULL, 'INTEREST_FIRST', TRUE, FALSE, TRUE)
                """);
    }

    @Test
    void addsFiveIndependentB01FixturesWithoutChangingExistingLoans() throws Exception {
        runSeed();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM loan WHERE customer_id=1", Integer.class))
                .isEqualTo(8);
        assertThat(jdbc.queryForMap("""
                SELECT balance, acceleration_declared, outsourced, agreement_loan,
                       repayment_order, has_third_party_provider
                  FROM loan WHERE loan_no='MOCK-LN-002'
                """))
                .containsEntry("balance", new java.math.BigDecimal("20000000.00"))
                .containsEntry("acceleration_declared", false)
                .containsEntry("outsourced", false)
                .containsEntry("agreement_loan", false)
                .containsEntry("repayment_order", "INTEREST_FIRST")
                .containsEntry("has_third_party_provider", false);

        assertFixture("MOCK-B01-T01", false, false, "INTEREST_FIRST", false);
        assertFixture("MOCK-B01-T02", true, false, "INTEREST_FIRST", false);
        assertFixture("MOCK-B01-T03", false, true, "INTEREST_FIRST", false);
        assertFixture("MOCK-B01-T04", false, false, "PRINCIPAL_FIRST", false);
        assertFixture("MOCK-B01-T05", false, false, "INTEREST_FIRST", true);
    }

    @Test
    void canRunTwiceWithoutDuplicatingFixturesAndA02ReturnsAllEightLoans() throws Exception {
        runSeed();
        runSeed();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM loan WHERE loan_no LIKE 'MOCK-B01-T%'
                """, Integer.class)).isEqualTo(5);
        assertThat(loans.findOwnedLoans(1, null))
                .hasSize(8)
                .extracting(result -> result.loanId())
                .isSorted();
    }

    private void assertFixture(
            String loanNo,
            boolean outsourced,
            boolean agreementLoan,
            String repaymentOrder,
            boolean thirdPartyProvider) {
        Map<String, Object> fixture = jdbc.queryForMap("""
                SELECT customer_id, acceleration_declared, acceleration_date,
                       outsourced, agreement_loan, repayment_order,
                       has_third_party_provider
                  FROM loan WHERE loan_no=?
                """, loanNo);
        assertThat(fixture)
                .containsEntry("acceleration_declared", true)
                .containsEntry("outsourced", outsourced)
                .containsEntry("agreement_loan", agreementLoan)
                .containsEntry("repayment_order", repaymentOrder)
                .containsEntry("has_third_party_provider", thirdPartyProvider);
        assertThat(((Number) fixture.get("customer_id")).longValue()).isEqualTo(1L);
        assertThat(fixture.get("acceleration_date")).isNotNull();
    }

    private void runSeed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/seed/b01_test_loans.sql"));
        }
    }
}
