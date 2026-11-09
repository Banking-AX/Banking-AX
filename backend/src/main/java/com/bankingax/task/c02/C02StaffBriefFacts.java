package com.bankingax.task.c02;

import com.bankingax.core.policychange.PolicyChangeReviewService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The settled facts of one Case, in the only form the LLM receives. Upload and content checks stay separate fields.
 * It carries no names, birth dates, account numbers, file names or OCR text, so nothing an attachment says can
 * reach the model as an instruction.
 */
final class C02StaffBriefFacts {
    private static final String POWER_OF_ATTORNEY = "ABSENT_HEIR_POWER_OF_ATTORNEY";
    private static final String OCR_PREFIX = "C02_POA_";
    private static final Map<String, String> FIELD_LABELS = Map.of(
            "C02_POA_depositorName", "예금주 성명", "C02_POA_agent.name", "대리인 성명", "C02_POA_delegators[].name", "위임인 성명",
            "C02_POA_accounts[].accountNo", "계좌번호", "C02_POA_depositorBirthDate", "예금주 생년월일", "C02_POA_agent.birthDate", "대리인 생년월일");
    private static final List<String> NEVER_COMPARED = List.of("주소", "본인과의 관계", "예금과목", "잔액", "작성일");

    private C02StaffBriefFacts() { }

    static Map<String, Object> of(C02IntakeService.StaffCaseOverview overview, Map<String, String> documentNames, List<Map<String, Object>> ocrDocuments) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", "상속예금 해지·지급 사전접수");
        facts.put("caseStatus", overview.caseView().caseStatus().name());
        facts.put("revision", overview.caseView().revision());
        facts.put("people", people(overview));
        List<Map<String, Object>> documents = documents(overview, documentNames, ocrDocuments);
        facts.put("documents", documents);
        facts.put("visitOnlyDocuments", overview.summary().visitOnlyCount());
        facts.put("missingRequiredDocuments", overview.summary().missingRequiredCount());
        facts.put("verification", verification(overview, documents));
        facts.put("staffMustConfirm", staffMustConfirm(overview));
        return facts;
    }

    /** STAFF-06 decisions on current files: upload, OCR and this staff check stay separate. Staff-written reasons are not sent. */
    static void addStaffChecks(Map<String, Object> facts, List<C02DocumentReviewService.Row> rows, Map<String, String> names) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (C02DocumentReviewService.Row row : rows) {
            if (row.documentFileId() == null) continue;
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("document", names.getOrDefault(row.documentTypeCode(), row.documentTypeCode()));
            check.put("staffCheck", row.review() == null ? "NOT_REVIEWED" : row.review().result());
            checks.add(check);
        }
        facts.put("staffDocumentChecks", checks);
    }

    static void addClarifications(Map<String, Object> facts, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", row.get("kind"));
            item.put("status", row.get("status"));
            out.add(item);
        }
        facts.put("customerClarifications", out);
    }

    /** Approved changes only, already narrowed to this Case. Pending bank events are never passed, so they cannot read as current policy. */
    static void addPolicy(Map<String, Object> facts, List<PolicyChangeReviewService.Notice> approved, long caseId) {
        if (approved == null) { facts.put("policyNoticesUnavailable", true); return; }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PolicyChangeReviewService.Notice notice : approved) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceVersion", notice.sourceVersion());
            row.put("summary", notice.summary());
            row.put("effectiveState", notice.effectiveState());
            row.put("effectiveAt", String.valueOf(notice.effectiveAt()));
            row.put("previousVersion", notice.previousVersion());
            row.put("before", notice.before());
            row.put("after", notice.after());
            row.put("addedCheckItems", notice.addedCheckItems());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("documentId", notice.sourceDocumentId());
            source.put("title", notice.sourceTitle());
            row.put("source", source);
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("taskCode", "INHERITANCE_DEPOSIT");
            scope.put("caseId", caseId);
            scope.put("reasonCodes", notice.reasonCodes());
            row.put("appliesTo", scope);
            rows.add(row);
        }
        facts.put("approvedPolicyChanges", rows);
    }

    private static Map<String, Object> people(C02IntakeService.StaffCaseOverview overview) {
        List<C02IntakeService.HeirView> heirs = overview.intake() == null ? List.of() : overview.intake().heirs();
        Map<String, Integer> delegation = new HashMap<>();
        for (C02IntakeService.HeirView heir : heirs) if (!"ATTENDING".equals(heir.visitStatus())) delegation.merge(heir.delegationMethod(), 1, Integer::sum);
        Map<String, Object> people = new LinkedHashMap<>();
        people.put("heirCount", heirs.size());
        people.put("attendingCount", heirs.stream().filter(heir -> "ATTENDING".equals(heir.visitStatus())).count());
        people.put("absentCount", heirs.stream().filter(heir -> "ABSENT".equals(heir.visitStatus())).count());
        people.put("unconfirmedCount", heirs.stream().filter(heir -> "UNCONFIRMED_ABSENT".equals(heir.visitStatus())).count());
        people.put("delegation", delegation);
        return people;
    }

    private static List<Map<String, Object>> documents(C02IntakeService.StaffCaseOverview overview, Map<String, String> names, List<Map<String, Object>> ocrDocuments) {
        Map<String, DocumentPort.DocumentMetadata> latest = new HashMap<>();
        for (DocumentPort.DocumentMetadata file : overview.documents())
            latest.merge(file.documentSlotKey(), file, (first, second) -> first.version() >= second.version() ? first : second);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (C02IntakeService.DocumentSlot slot : overview.requiredDocuments()) {
            DocumentPort.DocumentMetadata file = latest.get(slot.key());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", slot.documentTypeCode());
            row.put("name", names.getOrDefault(slot.documentTypeCode(), slot.documentTypeCode()));
            row.put("requirement", slot.requirement().name());
            row.put("submitted", file != null);
            row.put("ocrStatus", file == null ? "NO_FILE" : ocrStatus(file.documentFileId(), ocrDocuments));
            rows.add(row);
        }
        return rows;
    }

    private static String ocrStatus(long fileId, List<Map<String, Object>> ocrDocuments) {
        for (Map<String, Object> row : ocrDocuments) {
            if (!Objects.equals(((Number) row.get("documentFileId")).longValue(), fileId)) continue;
            String status = String.valueOf(row.get("status"));
            return "SUCCEEDED".equals(status) ? "EXTRACTED" : "NOT_APPLICABLE".equals(status) ? "NOT_APPLICABLE" : "FAILED";
        }
        return "NOT_RUN";
    }

    private static Map<String, Object> verification(C02IntakeService.StaffCaseOverview overview, List<Map<String, Object>> documents) {
        List<String> matched = new ArrayList<>(), mismatched = new ArrayList<>(), staffRequired = new ArrayList<>();
        String documentType = "NOT_CHECKED";
        for (Map<String, Object> item : overview.precheck().reviewItems()) {
            String category = String.valueOf(item.get("category"));
            if (!category.startsWith(OCR_PREFIX)) continue;
            String result = String.valueOf(item.get("result"));
            if ("C02_POA_DOCUMENT_TYPE".equals(category)) { documentType = result; continue; }
            String label = label(category);
            if ("AUTO_MATCHED".equals(result)) matched.add(label);
            else if ("MISMATCH".equals(result)) mismatched.add(label);
            else staffRequired.add(label);
        }
        List<String> notCompared = new ArrayList<>(NEVER_COMPARED);
        if (matched.stream().noneMatch(label -> label.contains("생년월일")) && mismatched.stream().noneMatch(label -> label.contains("생년월일"))
                && staffRequired.stream().noneMatch(label -> label.contains("생년월일"))) notCompared.add(0, "생년월일");
        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("ocrStatus", documents.stream().filter(row -> POWER_OF_ATTORNEY.equals(row.get("code"))).map(row -> String.valueOf(row.get("ocrStatus"))).findFirst().orElse("NOT_REQUIRED"));
        verification.put("documentType", documentType);
        verification.put("matched", matched);
        verification.put("mismatched", mismatched);
        verification.put("staffRequired", staffRequired);
        verification.put("notCompared", notCompared);
        return verification;
    }

    private static String label(String category) {
        String label = FIELD_LABELS.get(category);
        if (label != null) return label;
        return category.matches("C02_POA_delegators\\[\\d+]\\.birthDate") ? "위임인 생년월일" : "기타 위임장 항목";
    }

    private static List<String> staffMustConfirm(C02IntakeService.StaffCaseOverview overview) {
        List<String> items = new ArrayList<>(List.of("제출 서류의 원본과 내용", "상속관계"));
        boolean written = overview.intake() != null && overview.intake().heirs().stream().anyMatch(heir -> "DOCUMENT".equals(heir.delegationMethod()));
        if (written) items.addAll(List.of("위임인의 서명·인감", "실제 위임 의사"));
        return items;
    }
}
