package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the C-02 heirs and required-document slots after a real Case and
 * WorkItem have been persisted by the common workflow boundary.  It never
 * creates either parent record and therefore cannot bypass their FKs.
 */
@Service
@ConditionalOnProperty(name = "app.c02.document-provisioning-enabled", havingValue = "true")
public class C02HeirDocumentProvisioningService {
    private final JdbcTemplate jdbc;
    private final C02IntakeService c02Intake;

    public C02HeirDocumentProvisioningService(JdbcTemplate jdbc, C02IntakeService intake) {
        this.jdbc = jdbc;
        this.c02Intake = intake;
    }

    @Transactional
    public ProvisionedDocuments provision(ProvisionCommand command) {
        requireC02Case(command);
        validateHeirs(command.intake());
        for (C02IntakeService.HeirInput heir : command.intake().heirs()) {
            ensureDelegationReceipt(command.caseId(), heir);
            long heirId = ensureHeir(command.caseId(), heir);
            ensureSlots(command.workItemId(), command.caseId(), heirId, command.intake(), heir.personRef());
        }
        ensureSlots(command.workItemId(), command.caseId(), null, command.intake(), null);
        verifyDocumentConsistency(command.caseId());
        return new ProvisionedDocuments(command.caseId(), command.workItemId(),
            count("select count(*) from heir where case_id=?", command.caseId()),
            count("select count(*) from required_document where work_item_id=?", command.workItemId()));
    }

    private void requireC02Case(ProvisionCommand command) {
        List<CaseRow> rows = jdbc.query("""
            select sc.id as case_id, sc.work_item_id as work_item_id, sc.case_type as case_type
            from service_case sc where sc.id=?
            """, (rs, ignored) -> new CaseRow(rs.getLong("case_id"), rs.getLong("work_item_id"), rs.getString("case_type")), command.caseId());
        if (rows.isEmpty()) throw new DomainException("CASE_NOT_FOUND", "C-02 Case must already be persisted.");
        CaseRow row = rows.get(0);
        if (row.workItemId() != command.workItemId() || !"INHERITANCE_DEPOSIT".equals(row.caseType()))
            throw new DomainException("CASE_WORKITEM_MISMATCH", "Case does not belong to the supplied C-02 WorkItem.");
    }

    private void validateHeirs(C02IntakeService.Intake intake) {
        Set<String> personRefs = new HashSet<>();
        long applicants = intake.heirs().stream().filter(C02IntakeService.HeirInput::applicant).count();
        if (applicants != 1) throw new DomainException("ONE_APPLICANT_REQUIRED", "Exactly one applicant heir is required.");
        for (C02IntakeService.HeirInput heir : intake.heirs()) {
            if (heir.personRef() == null || heir.personRef().isBlank() || !personRefs.add(heir.personRef()))
                throw new DomainException("HEIR_PERSON_REF_INVALID", "Each heir personRef must be present and unique.");
        }
    }

    private long ensureHeir(long caseId, C02IntakeService.HeirInput heir) {
        List<HeirRow> existing = jdbc.query("""
            select id, name, birth_date, relationship, is_applicant, visit_status,
                   delegation_method, delegation_receipt_no
            from heir where case_id=? and person_ref=?
            """, (rs, ignored) -> new HeirRow(rs.getLong("id"), rs.getString("name"),
                rs.getObject("birth_date", java.time.LocalDate.class), rs.getString("relationship"),
                rs.getBoolean("is_applicant"), rs.getString("visit_status"), rs.getString("delegation_method"),
                rs.getString("delegation_receipt_no")), caseId, heir.personRef());
        if (!existing.isEmpty()) {
            HeirRow row = existing.get(0);
            if (!row.matches(heir)) throw new DomainException("HEIR_RETRY_CONFLICT", "personRef was retried with different heir data.");
            return row.id();
        }
        jdbc.update("""
            insert into heir(case_id,person_ref,name,birth_date,relationship,is_applicant,visit_status,delegation_method,delegation_receipt_no)
            values(?,?,?,?,?,?,?,?,?)
            """, caseId, heir.personRef(), heir.name(), heir.birthDate(), heir.relationship(), heir.applicant(),
            heir.visitStatus().name(), heir.delegationMethod().name(), blankToNull(heir.delegationReceiptNo()));
        return jdbc.queryForObject("select id from heir where case_id=? and person_ref=?", Long.class, caseId, heir.personRef());
    }

    private void ensureDelegationReceipt(long caseId, C02IntakeService.HeirInput heir) {
        if (heir.delegationMethod() != C02IntakeService.DelegationMethod.RECEIPT) return;
        Long decedentId = jdbc.queryForObject("select decedent_id from service_case where id=?", Long.class, caseId);
        Integer matches = jdbc.queryForObject("select count(*) from delegation_receipt where receipt_no=? and decedent_id=?", Integer.class,
            heir.delegationReceiptNo(), decedentId);
        if (matches == null || matches != 1) throw new DomainException("DELEGATION_RECEIPT_INVALID", "Delegation receipt is not valid for this Case decedent.");
    }

    private void ensureSlots(long workItemId, long caseId, Long heirId, C02IntakeService.Intake intake, String personRef) {
        for (C02IntakeService.DocumentSlot slot : c02Intake.requiredDocuments(intake)) {
            if (!java.util.Objects.equals(slot.personRef(), personRef)) continue;
            DocumentTypeRow type = documentType(slot.documentTypeCode());
            if (type.perPerson() != (heirId != null))
                throw new DomainException("DOCUMENT_TYPE_SCOPE_MISMATCH", "document_type per_person does not match the C-02 slot.");
            List<RequiredRow> existing = jdbc.query("""
                select id, requirement, status from required_document
                where work_item_id=? and document_type_code=? and ((? is null and heir_id is null) or heir_id=?)
                """, (rs, ignored) -> new RequiredRow(rs.getLong("id"), rs.getString("requirement"), rs.getString("status")),
                workItemId, slot.documentTypeCode(), heirId, heirId);
            if (!existing.isEmpty()) {
                if (!slot.requirement().name().equals(existing.get(0).requirement()))
                    throw new DomainException("DOCUMENT_REQUIREMENT_RETRY_CONFLICT", "Existing required-document rule differs from this retry.");
                continue;
            }
            jdbc.update("""
                insert into required_document(work_item_id,document_type_code,heir_id,requirement,condition_note,rule_version)
                values(?,?,?,?,?,?)
                """, workItemId, slot.documentTypeCode(), heirId, slot.requirement().name(), slot.note(), C02IntakeService.RULE_VERSION);
        }
    }

    private DocumentTypeRow documentType(String code) {
        List<DocumentTypeRow> types = jdbc.query("select per_person,is_active from document_type where code=?",
            (rs, ignored) -> new DocumentTypeRow(rs.getBoolean("per_person"), rs.getBoolean("is_active")), code);
        if (types.isEmpty() || !types.get(0).active())
            throw new DomainException("DOCUMENT_TYPE_UNAVAILABLE", "C-02 slot has no active matching document_type: " + code);
        return types.get(0);
    }

    private void verifyDocumentConsistency(long caseId) {
        List<RequiredRow> rows = jdbc.query("""
            select rd.id, rd.requirement, rd.status from required_document rd
            join work_item wi on wi.id=rd.work_item_id join service_case sc on sc.work_item_id=wi.id
            where sc.id=?
            """, (rs, ignored) -> new RequiredRow(rs.getLong("id"), rs.getString("requirement"), rs.getString("status")), caseId);
        for (RequiredRow row : rows) {
            int latest = count("select count(*) from document_file where required_document_id=? and is_latest=true and deleted_at is null", row.id());
            boolean requiresFile = !"NOT_SUBMITTED".equals(row.status());
            if ((requiresFile && latest != 1) || (!requiresFile && latest != 0))
                throw new DomainException("DOCUMENT_STATUS_INCONSISTENT", "required_document status and latest document_file disagree.");
        }
    }

    private int count(String sql, Object value) { return jdbc.queryForObject(sql, Integer.class, value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    public record ProvisionCommand(long caseId, long workItemId, C02IntakeService.Intake intake) {}
    public record ProvisionedDocuments(long caseId, long workItemId, int heirCount, int requiredDocumentCount) {}
    private record CaseRow(long caseId, long workItemId, String caseType) {}
    private record DocumentTypeRow(boolean perPerson, boolean active) {}
    private record RequiredRow(long id, String requirement, String status) {}
    private record HeirRow(long id, String name, java.time.LocalDate birthDate, String relationship, boolean applicant,
                           String visitStatus, String delegationMethod, String delegationReceiptNo) {
        boolean matches(C02IntakeService.HeirInput value) {
            return java.util.Objects.equals(name, value.name()) && java.util.Objects.equals(birthDate, value.birthDate())
                && java.util.Objects.equals(relationship, value.relationship()) && applicant == value.applicant()
                && java.util.Objects.equals(visitStatus, value.visitStatus().name())
                && java.util.Objects.equals(delegationMethod, value.delegationMethod().name())
                && java.util.Objects.equals(delegationReceiptNo, blankToNull(value.delegationReceiptNo()));
        }
    }
}
