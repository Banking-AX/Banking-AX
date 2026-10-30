package com.bankingax.task.a02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcLoanQueryPortConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withUserConfiguration(JdbcLoanQueryPort.class);

    @Test
    void startsWithJdbcAndJpa() {
        context.withPropertyValues("app.a02.loan-query-port=jdbc", "app.case-port=jpa")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(JdbcLoanQueryPort.class);
                });
    }

    @Test
    void failsWithJdbcAndInMemory() {
        assertInvalidCombination("app.a02.loan-query-port=jdbc", "app.case-port=inmemory");
    }

    @Test
    void failsWithJdbcWhenCasePortIsMissing() {
        assertInvalidCombination("app.a02.loan-query-port=jdbc");
    }

    @Test
    void fakeAndInMemoryDoesNotCreateTheJdbcAdapter() {
        context.withPropertyValues("app.a02.loan-query-port=fake", "app.case-port=inmemory")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).doesNotHaveBean(JdbcLoanQueryPort.class);
                });
    }

    @Test
    void productionCannotStartJdbcWithInMemoryValidation() {
        assertInvalidCombination(
                "spring.profiles.active=production",
                "app.a02.loan-query-port=jdbc",
                "app.case-port=inmemory");
    }

    private void assertInvalidCombination(String... properties) {
        context.withPropertyValues(properties).run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                            "APP_A02_LOAN_QUERY_PORT=jdbc requires APP_CASE_PORT=jpa for WorkItem validation.");
        });
    }
}
