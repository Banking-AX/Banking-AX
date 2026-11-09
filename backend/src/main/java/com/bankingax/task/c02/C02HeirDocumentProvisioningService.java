package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
    static final String RETIRED_NOTE = "RETIRED_BY_DRAFT_EDIT";
    private static final String POWER_OF_ATTORNEY = "ABSENT_HEIR_POWER_OF_ATTORNEY";

    private final JdbcTemplate jdbc;

    public C02HeirDocumentProvisioningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    /**
     * Brings heirs and slots in line with an edited DRAFT, in one transaction with the Case revision.
     * Nothing physical is deleted. A slot no longer needed keeps its files but stops counting (RETIRED_NOTE); it counts
     * again if the slot comes back. A heir removed with files stays as an inactive row, because files reference it.
     * Renaming someone voids that person's files, and any change to the people a power of attorney is compared with
     * voids that document: its stored OCR comparison was made against the old facts.
     */
    @Transactional
    public <T> T reconcile(ReconcileCommand command, Supplier<T> reviseCase) {
        ProvisionCommand target = new ProvisionCommand(command.caseId(), command.workItemId(), command.next());
        requireC02Case(target);
        validateHeirs(command.next());
        // uk_heir_one_applicant allows one applicant at any moment, so the flag moves in two steps.
        jdbc.update("update heir set is_applicant=false where case_id=?", command.caseId());
        Map<String, Long> heirIds = new HashMap<>();
        for (C02IntakeService.HeirInput heir : command.next().heirs()) {
            ensureDelegationReceipt(command.caseId(), heir);
            heirIds.put(heir.personRef(), upsertHeir(command.caseId(), heir));
        }
        syncSlots(command, heirIds);
        retireRemovedHeirs(command.caseId(), heirIds.keySet());
        voidOutdatedFiles(command);
        verifyDocumentConsistency(command.caseId());
        return reviseCase.get();
    }

    private long upsertHeir(long caseId, C02IntakeService.HeirInput heir) {
        int updated = jdbc.update("""
            update heir set name=?,birth_date=?,relationship=?,is_applicant=?,visit_status=?,delegation_method=?,delegation_receipt_no=?,version=version+1
            where case_id=? and person_ref=?
            """, heir.name(), heir.birthDate(), heir.relationship(), heir.applicant(), heir.visitStatus().name(), heir.delegationMethod().name(),
            blankToNull(heir.delegationReceiptNo()), caseId, heir.personRef());
        if (updated == 0) return ensureHeir(caseId, heir);
        return jdbc.queryForObject("select id from heir where case_id=? and person_ref=?", Long.class, caseId, heir.personRef());
    }

    private void syncSlots(ReconcileCommand command, Map<String, Long> heirIds) {
        Map<String, C02IntakeService.DocumentSlot> desired = new HashMap<>();
        for (C02IntakeService.DocumentSlot slot : C02IntakeService.requiredDocuments(command.next())) desired.put(slot.key(), slot);
        List<SlotRow> rows = slotRows(command.workItemId());
        for (SlotRow row : rows) {
            C02IntakeService.DocumentSlot slot = desired.remove(row.key());
            if (slot != null) jdbc.update("update required_document set requirement=?,condition_note=? where id=?", slot.requirement().name(), slot.note(), row.id());
            else if (row.files() > 0) jdbc.update("update required_document set requirement='OPTIONAL',condition_note=? where id=?", RETIRED_NOTE, row.id());
            else jdbc.update("delete from required_document where id=?", row.id());
        }
        for (C02IntakeService.DocumentSlot slot : desired.values()) {
            Long heirId = slot.personRef() == null ? null : heirIds.get(slot.personRef());
            if (documentType(slot.documentTypeCode()).perPerson() != (heirId != null))
                throw new DomainException("DOCUMENT_TYPE_SCOPE_MISMATCH", "document_type per_person does not match the C-02 slot.");
            jdbc.update("""
                insert into required_document(work_item_id,document_type_code,heir_id,requirement,condition_note,rule_version)
                values(?,?,?,?,?,?)
                """, command.workItemId(), slot.documentTypeCode(), heirId, slot.requirement().name(), slot.note(), C02IntakeService.RULE_VERSION);
        }
    }

    private List<SlotRow> slotRows(long workItemId) {
        return jdbc.query("""
            select rd.id, rd.document_type_code, h.person_ref,
                   (select count(*) from document_file f where f.required_document_id=rd.id) files
            from required_document rd left join heir h on h.id=rd.heir_id where rd.work_item_id=?
            """, (rs, ignored) -> new SlotRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)), workItemId);
    }

    private void retireRemovedHeirs(long caseId, Set<String> current) {
        List<HeirRef> heirs = jdbc.query("select id,person_ref from heir where case_id=?", (rs, ignored) -> new HeirRef(rs.getLong(1), rs.getString(2)), caseId);
        for (HeirRef heir : heirs) {
            if (current.contains(heir.personRef())) continue;
            if (count("select count(*) from required_document where heir_id=?", heir.id()) == 0) jdbc.update("delete from heir where id=?", heir.id());
            else jdbc.update("update heir set visit_status='UNKNOWN',delegation_method='NONE',delegation_receipt_no=null,version=version+1 where id=?", heir.id());
        }
    }

    private void voidOutdatedFiles(ReconcileCommand command) {
        Map<String, String> previousNames = new HashMap<>();
        command.previous().heirs().forEach(heir -> previousNames.put(heir.personRef(), heir.name()));
        for (C02IntakeService.HeirInput heir : command.next().heirs()) {
            String before = previousNames.get(heir.personRef());
            if (before == null || before.equals(heir.name())) continue;
            voidFiles("select rd.id from required_document rd join heir h on h.id=rd.heir_id where h.case_id=? and h.person_ref=?", command.caseId(), heir.personRef());
        }
        if (!comparedPeople(command.previous()).equals(comparedPeople(command.next())))
            voidFiles("select id from required_document where work_item_id=? and document_type_code=?", command.workItemId(), POWER_OF_ATTORNEY);
    }

    private void voidFiles(String requiredIdsSql, Object... arguments) {
        for (Long requiredId : jdbc.queryForList(requiredIdsSql, Long.class, arguments)) {
            jdbc.update("update document_file set is_latest=false,deleted_at=UTC_TIMESTAMP(6) where required_document_id=? and is_latest=true", requiredId);
            jdbc.update("update required_document set status='NOT_SUBMITTED' where id=?", requiredId);
        }
    }

    /** The values a stored power-of-attorney comparison was made against. */
    private static List<Object> comparedPeople(C02IntakeService.Intake intake) {
        List<Object> people = new ArrayList<>(List.of(String.valueOf(intake.decedent().name()), String.valueOf(intake.decedent().birthDate())));
        intake.heirs().stream().sorted(java.util.Comparator.comparing(C02IntakeService.HeirInput::personRef))
            .forEach(heir -> people.add(List.of(heir.personRef(), String.valueOf(heir.name()), String.valueOf(heir.birthDate()), heir.visitStatus().name())));
        return people;
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
        for (C02IntakeService.DocumentSlot slot : C02IntakeService.requiredDocuments(intake)) {
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
    public record ReconcileCommand(long caseId, long workItemId, C02IntakeService.Intake previous, C02IntakeService.Intake next) {}
    private record HeirRef(long id, String personRef) {}
    private record SlotRow(long id, String documentType, String personRef, int files) {
        String key() { return documentType + ":" + (personRef == null ? "COMMON" : personRef); }
    }
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
