package com.bankingax.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConditionalOnDataSourceTest {
    @Configuration
    @ConditionalOnDataSource
    static class Config {
        @Bean String jdbcBacked() { return "registered"; }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void registersWhenDataSourceAutoConfigurationIsNotExcluded() {
        runner.run(context -> assertThat(context).hasBean("jdbcBacked"));
        runner.withPropertyValues("spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
                .run(context -> assertThat(context).hasBean("jdbcBacked"));
    }

    @Test void skipsWhenTheInMemoryFixturesExcludeDataSourceAutoConfiguration() {
        runner.withPropertyValues("spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
                .run(context -> assertThat(context).doesNotHaveBean("jdbcBacked"));
        runner.withPropertyValues("spring.autoconfigure.exclude[0]=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
                .run(context -> assertThat(context).doesNotHaveBean("jdbcBacked"));
    }
}
