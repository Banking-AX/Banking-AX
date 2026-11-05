package com.bankingax.core.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers a JDBC-backed component unless the DataSource auto-configuration is excluded, which is how the
 * in-memory HTTP fixtures run without a database. This replaces {@code @ConditionalOnBean(JdbcTemplate.class)}:
 * on a component-scanned class that condition is evaluated before auto-configuration defines JdbcTemplate,
 * so it never matched in the running application.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnDataSource.DataSourceNotExcluded.class)
public @interface ConditionalOnDataSource {
    class DataSourceNotExcluded implements Condition {
        private static final String DATA_SOURCE_AUTO_CONFIGURATION =
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            List<String> excluded = Binder.get(context.getEnvironment())
                    .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).orElse(List.of());
            return excluded.stream().map(String::trim).noneMatch(DATA_SOURCE_AUTO_CONFIGURATION::equals);
        }
    }
}
