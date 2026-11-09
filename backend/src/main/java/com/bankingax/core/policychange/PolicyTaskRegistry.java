package com.bankingax.core.policychange;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Internal list of tasks. Task codes sent by the bank are checked against it, never trusted on their own. */
@Component
public class PolicyTaskRegistry {
    private final Set<String> taskCodes;
    private final Map<String, PolicyTaskProfile> profiles;

    public PolicyTaskRegistry(@Value("${app.policy-change.registered-tasks:}") List<String> configured,
                              ObjectProvider<PolicyTaskProfile> beans) {
        profiles = beans.orderedStream().collect(Collectors.toMap(PolicyTaskProfile::taskCode, Function.identity()));
        Set<String> codes = new LinkedHashSet<>(configured);
        codes.addAll(profiles.keySet());
        taskCodes = Set.copyOf(codes);
    }

    public Set<String> taskCodes() { return taskCodes; }

    public boolean registered(String taskCode) { return taskCodes.contains(taskCode); }

    public PolicyTaskProfile profile(String taskCode) {
        PolicyTaskProfile profile = profiles.get(taskCode);
        return profile != null ? profile : () -> taskCode;
    }
}
