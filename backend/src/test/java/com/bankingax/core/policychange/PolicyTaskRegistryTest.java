package com.bankingax.core.policychange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PolicyTaskRegistryTest {
    private static PolicyTaskRegistry registry(List<String> configured, PolicyTaskProfile... profiles) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (PolicyTaskProfile profile : profiles) beans.addBean(profile.taskCode(), profile);
        return new PolicyTaskRegistry(configured, beans.getBeanProvider(PolicyTaskProfile.class));
    }

    @Test void aConfiguredTaskWorksWithoutAProfile() {
        var registry = registry(List.of("NEW_TASK"));
        var open = new PolicyTaskProfile.OpenCase(7, 1, "SUBMITTED", null, null);

        assertThat(registry.registered("NEW_TASK")).isTrue();
        assertThat(registry.profile("NEW_TASK").assess(null, List.of(open))).isEqualTo(Map.of(7L, List.of("TASK_CODE_MATCH")));
    }

    @Test void aProfileBeanRegistersItsTask() {
        assertThat(registry(List.of(), () -> "PROFILED_TASK").registered("PROFILED_TASK")).isTrue();
    }

    @Test void anUnlistedTaskIsNotRegistered() {
        assertThat(registry(List.of("NEW_TASK")).registered("OTHER")).isFalse();
    }
}
