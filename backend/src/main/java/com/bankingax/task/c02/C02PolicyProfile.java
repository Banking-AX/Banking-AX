package com.bankingax.task.c02;

import com.bankingax.core.persistence.ConditionalOnDataSource;
import com.bankingax.core.policychange.PolicyChange;
import com.bankingax.core.policychange.PolicyTaskProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** C-02 narrowing: only Cases with the delegation method or document slot named by the change are candidates. */
@Component
@ConditionalOnDataSource
public class C02PolicyProfile implements PolicyTaskProfile {
    private static final String TASK_CODE = "INHERITANCE_DEPOSIT";
    private static final Set<String> METHODS = Set.of("DOCUMENT", "RECEIPT");

    private final JdbcTemplate jdbc;

    public C02PolicyProfile(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public String taskCode() { return TASK_CODE; }

    @Override public List<String> validateExtension(JsonNode extension) {
        if (extension.isMissingNode()) return List.of();
        return values(extension, "delegationMethods").stream().allMatch(METHODS::contains)
                ? List.of() : List.of("C02_EXTENSION_INVALID");
    }

    @Override public Map<Long, List<String>> assess(PolicyChange change, List<OpenCase> openCases) {
        JsonNode extension = change.extensionFor(TASK_CODE);
        if (extension.isMissingNode()) return PolicyTaskProfile.super.assess(change, openCases);
        Set<String> methods = values(extension, "delegationMethods");
        Set<String> documents = values(extension, "documentTypeCodes");
        Map<Long, List<String>> candidates = new LinkedHashMap<>();
        for (OpenCase openCase : openCases) {
            List<String> reasons = reasons(openCase.caseId(), methods, documents);
            if (!reasons.isEmpty()) candidates.put(openCase.caseId(), reasons);
        }
        return candidates;
    }

    private List<String> reasons(long caseId, Set<String> methods, Set<String> documents) {
        List<String> reasons = new ArrayList<>();
        var heirs = jdbc.queryForList("select visit_status,delegation_method from heir where case_id=?", caseId);
        if (heirs.stream().anyMatch(heir -> !"ATTENDING".equals(heir.get("visit_status")) && methods.contains(heir.get("delegation_method"))))
            reasons.add("DELEGATION_METHOD_MATCH");
        if (!documents.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(documents.size(), "?"));
            List<Object> arguments = new ArrayList<>(List.of(caseId));
            arguments.addAll(documents);
            Integer matches = jdbc.queryForObject("select count(*) from required_document rd join service_case sc on sc.work_item_id=rd.work_item_id "
                    + "where sc.id=? and rd.requirement<>'OPTIONAL' and rd.document_type_code in (" + placeholders + ")", Integer.class, arguments.toArray());
            if (matches != null && matches > 0) reasons.add("REQUIRED_DOCUMENT_MATCH");
        }
        return reasons;
    }

    private static Set<String> values(JsonNode extension, String field) {
        Set<String> values = new TreeSet<>();
        extension.path(field).forEach(value -> values.add(value.asText()));
        return values;
    }
}
