package com.bankingax.task.c02;

import com.bankingax.core.caseport.CasePort;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.caseport.ServiceCaseView;
import com.bankingax.core.caseport.ReviewStatus;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.*;
import com.bankingax.core.workflow.TaskCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.stereotype.Service;

/** C-02 deterministic intake rules. It never decides entitlement or staff outcome. */
@Service
public final class C02IntakeService {
    public static final String RULE_VERSION = "c02-g1";
    private static final LocalDate SUPPORTED_DEATH_DATE = LocalDate.of(2008, 1, 1);
    private final CasePort casePort;
    private final VerificationPort verification;
    private final StaffAgentPort staffAgent;
    private final Map<String, PreparedRequest> preparedRequests = new HashMap<>();
    private final Map<Long, Draft> drafts = new HashMap<>();
    private final Map<String, SubmittedRequest> submissions = new HashMap<>();

    public C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent) { this.casePort = casePort; this.verification=verification; this.staffAgent=staffAgent; }

    public Validation validate(Intake intake) {
        List<String> errors = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        if (intake.decedent().name().isBlank() || intake.decedent().deathDate() == null) errors.add("DECEDENT_REQUIRED");
        if (intake.heirs().isEmpty()) errors.add("HEIR_REQUIRED");
        if (intake.heirs().stream().filter(HeirInput::applicant).count() != 1) errors.add("ONE_APPLICANT_REQUIRED");
        if (intake.decedent().deathDate() != null && intake.decedent().deathDate().isBefore(SUPPORTED_DEATH_DATE)) outOfScope.add("DEATH_DATE_BEFORE_2008");
        if (intake.heirs().stream().anyMatch(h -> h.birthDate() != null && h.birthDate().plusYears(19).isAfter(LocalDate.now()))) outOfScope.add("MINOR_HEIR");
        for (HeirInput heir : intake.heirs()) {
            if (heir.personRef().isBlank() || heir.name().isBlank() || heir.relationship().isBlank()) errors.add("HEIR_FIELDS_REQUIRED:" + heir.personRef());
            if (heir.visitStatus() == VisitStatus.ABSENT && heir.delegationMethod() == DelegationMethod.NONE) errors.add("ABSENT_DELEGATION_REQUIRED:" + heir.personRef());
            if (heir.delegationMethod() == DelegationMethod.RECEIPT && (heir.delegationReceiptNo() == null || heir.delegationReceiptNo().isBlank())) errors.add("RECEIPT_REQUIRED:" + heir.personRef());
        }
        return new Validation(errors, outOfScope);
    }

    public List<DocumentSlot> requiredDocuments(Intake intake) {
        List<DocumentSlot> slots = new ArrayList<>(List.of(
            slot("DECEDENT_DETAILED_FAMILY_RELATION", Requirement.REQUIRED, null, "피상속인 공통"),
            slot("DECEDENT_BASIC_CERTIFICATE", Requirement.REQUIRED, null, "사망사실 확인"),
            slot("PASSBOOK_AND_SEAL", Requirement.VISIT_ONLY, null, "생략 가능")
        ));
        boolean higherOrder = intake.heirs().stream().anyMatch(h -> h.higherOrderOrSubrogation());
        if (higherOrder) slots.add(slot("DECEDENT_REMOVED_FAMILY_REGISTER", Requirement.CONDITIONAL, null, "3순위 이상 또는 대습상속"));
        for (HeirInput h : intake.heirs()) {
            if (h.visitStatus() == VisitStatus.ATTENDING) {
                slots.add(slot("HEIR_IDENTITY_CARD", Requirement.REQUIRED, h.personRef(), "방문 상속인"));
                slots.add(slot("HEIR_SEAL_OR_SIGNATURE", Requirement.VISIT_ONLY, h.personRef(), "방문 시 지참"));
            }
            if (h.visitStatus() == VisitStatus.ABSENT || h.visitStatus() == VisitStatus.UNCONFIRMED_ABSENT) {
                if (h.delegationMethod() == DelegationMethod.DOCUMENT) {
                    slots.add(slot("ABSENT_HEIR_ID_COPY", Requirement.REQUIRED, h.personRef(), "불참 위임"));
                    slots.add(slot("ABSENT_HEIR_SEAL_CERTIFICATE", Requirement.REQUIRED, h.personRef(), "3개월 이내·본인발급용"));
                    slots.add(slot("ABSENT_HEIR_POWER_OF_ATTORNEY", Requirement.REQUIRED, h.personRef(), "사전 작성 위임장"));
                } else if (h.delegationMethod() == DelegationMethod.RECEIPT) slots.add(slot("DELEGATION_RECEIPT_NO", Requirement.REQUIRED, h.personRef(), "슈퍼SOL 위임장 접수번호"));
            }
        }
        return slots;
    }

    public Submission assessSubmission(Intake intake, Map<String, DocumentStatus> statuses) {
        Validation validation = validate(intake);
        if (!validation.outOfScope().isEmpty()) return new Submission(false, validation.errors(), validation.outOfScope(), List.of());
        List<String> missing = new ArrayList<>();
        for (DocumentSlot slot : requiredDocuments(intake)) if (slot.requirement() == Requirement.REQUIRED && statuses.getOrDefault(slot.key(), DocumentStatus.NOT_SUBMITTED) == DocumentStatus.NOT_SUBMITTED) missing.add(slot.key());
        return new Submission(validation.errors().isEmpty() && missing.isEmpty(), validation.errors(), List.of(), missing);
    }

    public ServiceCaseView createDraft(long customerId, long workItemId, Intake intake) {
        return casePort.create(new CreateCaseCommand(customerId, workItemId, TaskCode.INHERITANCE_DEPOSIT, null, Map.of("c02", intake), null, true));
    }
    public synchronized Prepared prepare(long customerId, long workItemId, String requestId, Intake intake, Map<String, DocumentStatus> statuses) { return prepare(customerId,workItemId,requestId,intake,statuses,null); }
    public synchronized Prepared prepare(long customerId, long workItemId, String requestId, Intake intake, Map<String, DocumentStatus> statuses, String registeredPhone) {
        Fingerprint fingerprint = new Fingerprint(customerId, workItemId, intake, statuses);
        PreparedRequest existing = preparedRequests.get(requestId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new com.bankingax.core.api.DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another C-02 preparation.");
            return existing.prepared();
        }
        Submission submission = assessSubmission(intake, statuses);
        ServiceCaseView view = submission.outOfScope().isEmpty() ? createDraft(customerId, workItemId, intake) : null;
        if(view != null) drafts.put(view.caseId(),new Draft(customerId,view,intake,Map.copyOf(statuses),registeredPhone));
        Prepared result = new Prepared(view, requiredDocuments(intake), submission);
        preparedRequests.put(requestId, new PreparedRequest(fingerprint, result));
        return result;
    }
    public synchronized C02Draft recordConsentAndMockSignature(long customerId,long caseId,int revision,boolean consented,boolean mockSignatureCompleted) {
        Draft draft=owned(customerId,caseId); requireRevision(draft,revision); draft.consented=consented; draft.mockSignatureCompleted=mockSignatureCompleted; return draft.view();
    }
    public synchronized VerificationIssue issueOtp(long customerId,long caseId,int revision) {
        Draft draft=owned(customerId,caseId); requireRevision(draft,revision); if(!draft.consented||!draft.mockSignatureCompleted) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","필수 동의와 모의 서명이 필요합니다.");
        return verification.issue(new VerificationIssueCommand(customerId,draft.view.workItemId(),VerificationAction.C02_SUBMIT,revision,draft.phone));
    }
    public VerificationBinding verifyOtp(long customerId,String challengeId,String otp) { return verification.verify(new VerificationVerifyCommand(customerId,challengeId,otp)); }
    public synchronized SubmissionResult submit(C02SubmitCommand command) {
        SubmittedRequest previous=submissions.get(command.requestId());
        if(previous!=null) { if(!previous.command().equals(command)) throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused for another C-02 submission."); return previous.result(); }
        Draft draft=owned(command.customerId(),command.caseId()); requireRevision(draft,command.expectedRevision());
        Submission assessment=assessSubmission(draft.intake,draft.documentStatuses);
        if(!assessment.submittable()) throw new DomainException("C02_SUBMISSION_BLOCKED","C-02 input, scope, or required document slots are incomplete.");
        if(!draft.consented||!draft.mockSignatureCompleted) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","필수 동의와 모의 서명이 필요합니다.");
        verification.consume(new VerificationConsumeCommand(command.bindingId(),command.customerId(),draft.view.workItemId(),VerificationAction.C02_SUBMIT,command.expectedRevision()));
        ServiceCaseView submitted=casePort.submit(command.customerId(),command.caseId(),command.expectedRevision(),command.requestId()); draft.view=submitted;
        StaffAgentPort.StaffAgentResponse report;
        try { report=dispatchStaffCheck(draft,command.requestId());
        } catch (DomainException exception) {
            casePort.recordCheckResult(submitted.caseId(),submitted.revision(),ReviewStatus.FAILED_NEEDS_STAFF);
            report=new StaffAgentPort.StaffAgentResponse("FAILED","FAILED_NEEDS_STAFF",List.of(),Map.of("code",exception.code(),"message",exception.getMessage()));
        }
        SubmissionResult result=new SubmissionResult(command.customerId(),submitted.caseId(),submitted.workItemId(),submitted.revision(),submitted.caseStatus().name(),report.status(),report.reviewStatus(),report.reviewItems(),report.error());
        submissions.put(command.requestId(),new SubmittedRequest(command,result)); return result;
    }
    private StaffAgentPort.StaffAgentResponse dispatchStaffCheck(Draft draft,String requestId) {
        ServiceCaseView latest=casePort.get(draft.customerId,draft.view.caseId()); requireRevision(draft,latest.revision());
        if(latest.caseStatus()!=com.bankingax.core.caseport.CaseStatus.SUBMITTED) throw new DomainException("INVALID_STATE","Only submitted cases can be checked.");
        StaffAgentPort.StaffAgentResponse report=staffAgent.check(requestId,latest.caseId(),latest.revision(),snapshot(latest,draft));
        ServiceCaseView after=casePort.get(draft.customerId,latest.caseId());
        if(after.revision()!=latest.revision()) throw new DomainException("VERSION_CONFLICT","Case revision changed while the staff Agent was running.",true);
        ReviewStatus status;
        if(!"SUCCEEDED".equals(report.status())) status=ReviewStatus.FAILED_NEEDS_STAFF;
        else try { status=ReviewStatus.valueOf(report.reviewStatus()); }
        catch (IllegalArgumentException | NullPointerException exception) { throw new DomainException("STAFF_AGENT_INVALID_RESPONSE","Staff Agent returned an invalid review status.",true); }
        casePort.recordCheckResult(after.caseId(),after.revision(),status); return report;
    }
    private Map<String,Object> snapshot(ServiceCaseView view,Draft draft) {
        List<Map<String,Object>> heirs=draft.intake.heirs().stream().map(h -> Map.<String,Object>of("personRef",h.personRef(),"visitStatus",h.visitStatus().name(),"delegationMethod",h.delegationMethod().name())).toList();
        Submission assessment=assessSubmission(draft.intake,draft.documentStatuses);
        return Map.of("caseStatus",view.caseStatus().name(),"reviewStatus",String.valueOf(view.reviewStatus()),"decedent",Map.of("deathDate",draft.intake.decedent().deathDate().toString()),"heirs",heirs,"outOfScope",assessment.outOfScope(),"missingDocuments",assessment.missingDocuments());
    }
    private Draft owned(long customerId,long caseId) { Draft draft=drafts.get(caseId); if(draft==null) throw new DomainException("NOT_FOUND","C-02 draft was not found."); if(draft.customerId!=customerId) throw new DomainException("FORBIDDEN","Case ownership does not match."); return draft; }
    private static void requireRevision(Draft draft,int revision) { if(draft.view.revision()!=revision) throw new DomainException("VERSION_CONFLICT","Case revision is stale.",true); }
    private static DocumentSlot slot(String code, Requirement requirement, String personRef, String note) { return new DocumentSlot(code, requirement, personRef, note); }

    public record DecedentInput(String name, LocalDate birthDate, LocalDate deathDate, long depositTotal) {}
    public record HeirInput(String personRef, String name, LocalDate birthDate, String relationship, boolean applicant, boolean higherOrderOrSubrogation, VisitStatus visitStatus, DelegationMethod delegationMethod, String delegationReceiptNo) {}
    public record Intake(DecedentInput decedent, List<HeirInput> heirs) {}
    public record Validation(List<String> errors, List<String> outOfScope) {}
    public record DocumentSlot(String documentTypeCode, Requirement requirement, String personRef, String note) { public String key(){ return documentTypeCode + ":" + (personRef == null ? "COMMON" : personRef); } }
    public record Submission(boolean submittable, List<String> errors, List<String> outOfScope, List<String> missingDocuments) {}
    public record Prepared(ServiceCaseView caseView, List<DocumentSlot> requiredDocuments, Submission submission) {}
    public record C02Draft(long caseId,long workItemId,int revision,String caseStatus,String reviewStatus,boolean visitRequired) {}
    public record C02SubmitCommand(long customerId,long caseId,int expectedRevision,String requestId,String bindingId) {}
    public record SubmissionResult(long customerId,long caseId,long workItemId,int revision,String caseStatus,String agentRunStatus,String reviewStatus,List<Map<String,Object>> reviewItems,Map<String,Object> error) {}
    public record Fingerprint(long customerId, long workItemId, Intake intake, Map<String, DocumentStatus> documentStatuses) {}
    private record PreparedRequest(Fingerprint fingerprint, Prepared prepared) {}
    private record SubmittedRequest(C02SubmitCommand command, SubmissionResult result) {}
    private static final class Draft { final long customerId; final Intake intake; final Map<String,DocumentStatus> documentStatuses; final String phone; ServiceCaseView view; boolean consented; boolean mockSignatureCompleted;
        Draft(long customerId,ServiceCaseView view,Intake intake,Map<String,DocumentStatus> documentStatuses,String phone){this.customerId=customerId;this.view=view;this.intake=intake;this.documentStatuses=documentStatuses;this.phone=phone;}
        C02Draft view(){return new C02Draft(view.caseId(),view.workItemId(),view.revision(),view.caseStatus().name(),view.reviewStatus()==null?null:view.reviewStatus().name(),view.visitRequired());}
    }
    public enum VisitStatus { UNKNOWN, ATTENDING, ABSENT, UNCONFIRMED_ABSENT }
    public enum DelegationMethod { NONE, DOCUMENT, RECEIPT }
    public enum Requirement { REQUIRED, CONDITIONAL, VISIT_ONLY }
    public enum DocumentStatus { NOT_SUBMITTED, UPLOADED, VALIDATION_FAILED, SYSTEM_VALIDATED, HUMAN_VERIFIED, NEEDS_SUPPLEMENT }
}
