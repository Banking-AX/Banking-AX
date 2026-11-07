package com.bankingax.task.c02;

import com.bankingax.core.caseport.CasePort;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.caseport.ServiceCaseView;
import com.bankingax.core.caseport.ReviewStatus;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.notification.NotificationWriter;
import com.bankingax.core.verification.*;
import com.bankingax.core.workflow.TaskCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** C-02 deterministic intake rules. It never decides entitlement or staff outcome. */
@Service
public final class C02IntakeService {
    public static final String RULE_VERSION = "c02-g1";
    private static final LocalDate SUPPORTED_DEATH_DATE = LocalDate.of(2008, 1, 1);
    private final CasePort casePort;
    private final VerificationPort verification;
    private final StaffAgentPort staffAgent;
    private final DocumentPort documents;
    private final C02FileStorage files;
    private final C02AgentReviewPersistence reviewPersistence;
    private final C02StaffDecisionPersistence decisionPersistence;
    private final C02HeirDocumentProvisioningService documentProvisioning;
    private final OcrPersistence ocrPersistence;
    private final NotificationWriter notificationWriter;
    private final Map<String, PreparedRequest> preparedRequests = new HashMap<>();
    private final Map<Long, Draft> drafts = new HashMap<>();
    private final Map<String, SubmittedRequest> submissions = new HashMap<>();
    private final Map<String, PrecheckRequest> prechecks = new HashMap<>();
    private final Map<String, DocumentUpdate> documentUpdates = new HashMap<>();
    private final Map<ReviewKey, StaffPrecheckResult> reviewResults = new HashMap<>();
    /** G3 development-only staff outcome and web-notification store. It is intentionally not a CasePort substitute. */
    private final Map<String, StaffDecisionResult> staffDecisionsByRequest = new HashMap<>();
    private final Map<Long, StaffDecisionResult> staffDecisionsByCase = new HashMap<>();
    private final Map<Long, List<CustomerNotification>> notificationsByCustomer = new HashMap<>();

    public C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent) { this(casePort, verification, staffAgent, new InMemoryDocumentPort(), null, (C02AgentReviewPersistence)null, null, null, null, null); }
    public C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent, DocumentPort documents) { this(casePort, verification, staffAgent, documents, null, (C02AgentReviewPersistence)null, null, null, null, null); }
    public C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent, DocumentPort documents, C02FileStorage files) { this(casePort, verification, staffAgent, documents, files, (C02AgentReviewPersistence)null, null, null, null, null); }
    @Autowired public C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent, DocumentPort documents, C02FileStorage files,
            org.springframework.beans.factory.ObjectProvider<C02AgentReviewPersistence> reviewPersistence,
            org.springframework.beans.factory.ObjectProvider<C02StaffDecisionPersistence> decisionPersistence,
            org.springframework.beans.factory.ObjectProvider<C02HeirDocumentProvisioningService> documentProvisioning,
            org.springframework.beans.factory.ObjectProvider<OcrPersistence> ocrPersistence,
            org.springframework.beans.factory.ObjectProvider<NotificationWriter> notificationWriter) {
        this(casePort,verification,staffAgent,documents,files,reviewPersistence.getIfAvailable(),decisionPersistence.getIfAvailable(),documentProvisioning.getIfAvailable(),ocrPersistence.getIfAvailable(),notificationWriter.getIfAvailable());
    }
    private C02IntakeService(CasePort casePort, VerificationPort verification, StaffAgentPort staffAgent, DocumentPort documents, C02FileStorage files,
            C02AgentReviewPersistence reviewPersistence, C02StaffDecisionPersistence decisionPersistence, C02HeirDocumentProvisioningService documentProvisioning, OcrPersistence ocrPersistence,
            NotificationWriter notificationWriter) {
        this.casePort = casePort; this.verification=verification; this.staffAgent=staffAgent; this.documents=documents; this.files=files;
        this.reviewPersistence=reviewPersistence; this.decisionPersistence=decisionPersistence; this.documentProvisioning=documentProvisioning; this.ocrPersistence=ocrPersistence;
        this.notificationWriter=notificationWriter;
    }

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

    public static List<DocumentSlot> requiredDocuments(Intake intake) {
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
        for (DocumentSlot slot : requiredDocuments(intake)) {
            DocumentStatus status=statuses.getOrDefault(slot.key(),DocumentStatus.NOT_SUBMITTED);
            if (slot.requirement()==Requirement.REQUIRED && (status==DocumentStatus.NOT_SUBMITTED || status==DocumentStatus.VALIDATION_FAILED || status==DocumentStatus.NEEDS_SUPPLEMENT)) missing.add(slot.key());
        }
        return new Submission(validation.errors().isEmpty() && missing.isEmpty(), validation.errors(), List.of(), missing);
    }

    public ServiceCaseView createDraft(long customerId, long workItemId, Intake intake) {
        return casePort.create(new CreateCaseCommand(customerId, workItemId, TaskCode.INHERITANCE_DEPOSIT, null, Map.of("c02", intake), null, true));
    }
    /** Reopens an owned C-02 draft without invoking create(), preserving its stored application and revision. */
    public synchronized Prepared resume(long customerId, long workItemId) {
        ServiceCaseView view=casePort.findByWorkItem(customerId,workItemId,TaskCode.INHERITANCE_DEPOSIT)
            .orElseThrow(() -> new DomainException("NOT_FOUND","No C-02 case is linked to this work item."));
        if (view.caseStatus()!=com.bankingax.core.caseport.CaseStatus.DRAFT)
            throw new DomainException("INVALID_STATE","Only a draft C-02 case can be resumed.");
        Draft draft=owned(customerId,view.caseId());
        return new Prepared(view,requiredDocuments(draft.intake),assessSubmission(draft.intake,serverDocumentStatuses(draft)));
    }
    public synchronized Prepared prepare(long customerId, long workItemId, String requestId, Intake intake, Map<String, DocumentStatus> statuses) { return prepare(customerId,workItemId,requestId,intake,statuses,null); }
    public synchronized Prepared prepare(long customerId, long workItemId, String requestId, Intake intake, Map<String, DocumentStatus> statuses, String registeredPhone) {
        Fingerprint fingerprint = new Fingerprint(customerId, workItemId, intake, statuses);
        PreparedRequest existing = preparedRequests.get(requestId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new com.bankingax.core.api.DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another C-02 preparation.");
            return existing.prepared();
        }
        var existingCase=casePort.findByWorkItem(customerId,workItemId,TaskCode.INHERITANCE_DEPOSIT);
        if (existingCase.isPresent()) {
            ServiceCaseView existingView=existingCase.get();
            if (existingView.caseStatus()!=com.bankingax.core.caseport.CaseStatus.DRAFT)
                throw new DomainException("INVALID_STATE","Only a draft C-02 case can be prepared again.");
            Draft draft=owned(customerId,existingView.caseId());
            ServiceCaseView latest=casePort.get(customerId,existingView.caseId());
            if (draft.view.revision()!=latest.revision()) draft=restore(customerId,existingView.caseId());
            Prepared result;
            if (draft.intake.equals(intake)) {
                // A browser refresh may use a new key after the original response was lost.  It is
                // still the same logical preparation, so do not create a needless Case revision.
                result=new Prepared(draft.view,requiredDocuments(draft.intake),assessSubmission(draft.intake,serverDocumentStatuses(draft)));
            } else {
                Draft editing=draft;
                java.util.function.Supplier<ServiceCaseView> revise=()->casePort.revise(customerId,editing.view.caseId(),editing.view.revision(),Map.of("c02",intake),"C02_PREPARE_UPDATED");
                ServiceCaseView revised=documentProvisioning==null?revise.get():documentProvisioning.reconcile(new C02HeirDocumentProvisioningService.ReconcileCommand(draft.view.caseId(),draft.view.workItemId(),draft.intake,intake),revise);
                draft.view=revised; draft.intake=intake; draft.consented=false; draft.mockSignatureCompleted=false;
                result=new Prepared(revised,requiredDocuments(intake),assessSubmission(intake,serverDocumentStatuses(draft)));
            }
            preparedRequests.put(requestId,new PreparedRequest(fingerprint,result));
            return result;
        }
        Submission submission = assessSubmission(intake, statuses);
        ServiceCaseView view = submission.outOfScope().isEmpty() ? createDraft(customerId, workItemId, intake) : null;
        if(view != null) {
            Draft draft = new Draft(customerId,view,intake,Map.copyOf(statuses),registeredPhone);
            drafts.put(view.caseId(),draft);
            if (documentProvisioning != null) documentProvisioning.provision(new C02HeirDocumentProvisioningService.ProvisionCommand(view.caseId(),view.workItemId(),intake));
        }
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
        Draft draft=submittableDraft(command);
        verification.consume(new VerificationConsumeCommand(command.bindingId(),command.customerId(),draft.view.workItemId(),VerificationAction.C02_SUBMIT,command.expectedRevision()));
        return completeSubmission(command,draft);
    }
    /**
     * Dev demo-seed entry (C02DemoSeeder only, never in the production profile): the same input,
     * document, consent and revision checks and the same post-submit path as {@link #submit},
     * without the SMS binding that the SOLAPI module will provide.
     */
    synchronized SubmissionResult submitForDemoSeed(long customerId,long caseId,int revision,String requestId) {
        C02SubmitCommand command=new C02SubmitCommand(customerId,caseId,revision,requestId,DEMO_SEED_BINDING);
        SubmittedRequest previous=submissions.get(requestId);
        if(previous!=null) return previous.result();
        return completeSubmission(command,submittableDraft(command));
    }
    static final String DEMO_SEED_BINDING="DEMO_SEED_WITHOUT_SMS";
    private Draft submittableDraft(C02SubmitCommand command) {
        Draft draft=owned(command.customerId(),command.caseId()); requireRevision(draft,command.expectedRevision());
        Submission assessment=assessSubmission(draft.intake,serverDocumentStatuses(draft));
        if(!assessment.submittable()) throw new DomainException("C02_SUBMISSION_BLOCKED","C-02 input, scope, or required document slots are incomplete.");
        if(!draft.consented||!draft.mockSignatureCompleted) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","필수 동의와 모의 서명이 필요합니다.");
        return draft;
    }
    /** Everything after the verification binding is consumed: Case SUBMITTED, receipt notice, staff Agent precheck. */
    private SubmissionResult completeSubmission(C02SubmitCommand command,Draft draft) {
        ServiceCaseView submitted=casePort.submit(command.customerId(),command.caseId(),command.expectedRevision(),command.requestId()); draft.view=submitted;
        if (decisionPersistence != null) decisionPersistence.markSubmittedAt(submitted.caseId());
        notifyReceived(command.customerId(),submitted);
        StaffAgentPort.StaffAgentResponse report;
        try { report=dispatchStaffCheck(draft,command.requestId());
        } catch (RuntimeException exception) {
            draft.view=casePort.recordCheckResult(submitted.caseId(),submitted.revision(),ReviewStatus.FAILED_NEEDS_STAFF);
            report=new StaffAgentPort.StaffAgentResponse("FAILED","FAILED_NEEDS_STAFF",List.of(),precheckFailure(submitted.caseId(),exception));
            reviewResults.put(new ReviewKey(submitted.caseId(),submitted.revision()),new StaffPrecheckResult(submitted.caseId(),submitted.workItemId(),submitted.revision(),report.status(),report.reviewStatus(),report.reviewItems(),report.error(),false));
        }
        SubmissionResult result=new SubmissionResult(command.customerId(),submitted.caseId(),submitted.workItemId(),submitted.revision(),submitted.caseStatus().name(),report.status(),report.reviewStatus(),report.reviewItems(),report.error());
        submissions.put(command.requestId(),new SubmittedRequest(command,result)); return result;
    }
    /** The customer's submission already stands, so any precheck failure must leave a staff path, not a stuck PENDING Case. */
    private static Map<String,Object> precheckFailure(long caseId,RuntimeException exception) {
        if(exception instanceof DomainException domain) return Map.of("code",domain.code(),"message",domain.getMessage());
        System.getLogger(C02IntakeService.class.getName()).log(System.Logger.Level.ERROR,"C-02 staff precheck failed unexpectedly for case "+caseId,exception);
        return Map.of("code","STAFF_PRECHECK_INTERNAL_ERROR","message","Staff precheck failed; staff review is required.");
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
        if (reviewPersistence != null) reviewPersistence.persist(requestId, after.caseId(), after.revision(), report);
        draft.view=casePort.recordCheckResult(after.caseId(),after.revision(),status);
        reviewResults.put(new ReviewKey(after.caseId(), after.revision()), new StaffPrecheckResult(after.caseId(), after.workItemId(), after.revision(), report.status(), status.name(), report.reviewItems(), report.error(), false));
        return report;
    }
    /** Registers metadata only. File bytes/storage remain outside this development adapter. */
    public synchronized DocumentUpdate registerDocument(long customerId, long caseId, int expectedRevision, String requestId, DocumentPort.DocumentRegistration registration) {
        DocumentUpdate previous=documentUpdates.get(requestId);
        if(previous!=null) return previous;
        Draft draft=owned(customerId,caseId); requireRevision(draft,expectedRevision);
        if(registration.caseId()!=caseId || requiredDocuments(draft.intake).stream().noneMatch(slot -> slot.key().equals(registration.documentSlotKey())))
            throw new DomainException("DOCUMENT_SLOT_UNKNOWN","The document slot is not required for this C-02 case.");
        DocumentPort.DocumentMetadata metadata=documents.register(new DocumentPort.DocumentRegistration(caseId,expectedRevision,registration.documentSlotKey(),registration.fileName(),registration.mimeType(),registration.sizeBytes(),registration.storageReference(),registration.status(),documentType(registration.documentSlotKey()),registration.checksum(),java.time.Instant.now(),customerId));
        try { draft.view=casePort.revise(customerId,caseId,expectedRevision,draft.view.application(),"DOCUMENT_METADATA_REGISTERED"); }
        catch(RuntimeException error) { documents.delete(metadata.documentFileId()); throw error; }
        StaffPrecheckResult precheck=draft.view.caseStatus()==com.bankingax.core.caseport.CaseStatus.SUBMITTED ? runStaffPrecheckInternal(draft,requestId+"-precheck") : null;
        staffDecisionsByCase.remove(caseId);
        DocumentUpdate update=new DocumentUpdate(metadata,draft.view.revision(),draft.view.reviewStatus()==null?null:draft.view.reviewStatus().name(),precheck);
        documentUpdates.put(requestId,update); return update;
    }
    public synchronized DocumentUpdate uploadDocument(long customerId,long caseId,int expectedRevision,String requestId,String slot,org.springframework.web.multipart.MultipartFile upload) {
        DocumentUpdate replay=documentUpdates.get(requestId); if(replay!=null)return replay;
        Draft draft=owned(customerId,caseId); requireRevision(draft,expectedRevision);
        if(requiredDocuments(draft.intake).stream().noneMatch(item->item.key().equals(slot)))throw new DomainException("DOCUMENT_SLOT_UNKNOWN","The document slot is not required for this C-02 case.");
        if(files==null)throw new DomainException("DOCUMENT_STORAGE_UNAVAILABLE","Local document storage is not configured.",true);
        C02FileStorage.StoredFile stored=files.store(upload);
        try { return registerDocument(customerId,caseId,expectedRevision,requestId,new DocumentPort.DocumentRegistration(caseId,expectedRevision,slot,stored.originalFilename(),stored.contentType(),stored.fileSize(),stored.storageKey(),DocumentStatus.UPLOADED,documentType(slot),stored.checksum(),java.time.Instant.now(),customerId)); }
        catch(RuntimeException error) { files.deleteQuietly(stored.storageKey()); throw error; }
    }
    public synchronized FileDownload customerDocument(long customerId,long caseId,long documentId) { return download(owned(customerId,caseId),documentId); }
    public synchronized FileDownload staffDocument(long caseId,long documentId) { return download(staffDraft(caseId),documentId); }
    private FileDownload download(Draft draft,long documentId) { DocumentPort.DocumentMetadata meta=documents.findById(documentId).orElseThrow(()->new DomainException("DOCUMENT_NOT_FOUND","Document metadata was not found.")); if(meta.caseId()!=draft.view.caseId())throw new DomainException("FORBIDDEN","Document does not belong to this C-02 case."); if(files==null)throw new DomainException("DOCUMENT_STORAGE_UNAVAILABLE","Local document storage is not configured.",true); return new FileDownload(meta,files.open(meta.storageReference())); }
    public synchronized List<DocumentPort.DocumentMetadata> documents(long customerId,long caseId) { owned(customerId,caseId); return documents.findByCase(caseId); }
    /** Returns the live case revision only after verifying the requesting customer's ownership. */
    public synchronized int currentRevisionForCustomer(long customerId,long caseId) {
        Draft draft=owned(customerId,caseId); ServiceCaseView latest=casePort.get(customerId,caseId);
        draft.view=latest; return latest.revision();
    }
    /** Staff boundary uses the same current Case revision as staff-case and final decision. */
    public synchronized int currentRevisionForStaff(long caseId) {
        Draft draft=staffDraft(caseId); ServiceCaseView latest=casePort.get(draft.customerId,caseId);
        draft.view=latest; return latest.revision();
    }
    public synchronized StaffPrecheckResult customerReview(long customerId,long caseId) { return reviewFor(owned(customerId,caseId)); }
    /** Customer-owned durable state used to restore a C-02 Case after browser memory is lost. */
    public synchronized CustomerCaseSnapshot customerCaseSnapshot(long customerId,long caseId) {
        Draft draft=owned(customerId,caseId); ServiceCaseView latest=casePort.get(customerId,caseId);
        if(draft.view.revision()!=latest.revision()) draft=restore(customerId,caseId);
        draft.view=latest;
        C02StaffDecisionPersistence.Timeline timeline=decisionPersistence==null?null:decisionPersistence.timeline(caseId);
        return new CustomerCaseSnapshot(latest.caseId(),latest.workItemId(),latest.revision(),latest.caseStatus().name(),
            latest.reviewStatus()==null?null:latest.reviewStatus().name(),latest.visitRequired(),requiredDocuments(draft.intake),
            documents.findByCase(caseId),reviewFor(draft),intakeView(draft.intake),timeline,customerDecision(caseId,timeline));
    }
    /** Display-only echo of what the customer entered; heir birth dates are not returned. */
    private static CaseIntakeView intakeView(Intake intake) {
        return new CaseIntakeView(intake.decedent().name(),intake.decedent().birthDate(),intake.decedent().deathDate(),intake.decedent().depositTotal(),
            intake.heirs().stream().map(h -> new HeirView(h.personRef(),h.name(),h.birthDate(),h.relationship(),h.applicant(),h.visitStatus().name(),h.delegationMethod().name())).toList());
    }
    /** A staff outcome is shown to the customer only after the result notification was recorded. */
    private CustomerDecision customerDecision(long caseId,C02StaffDecisionPersistence.Timeline timeline) {
        if (decisionPersistence != null) {
            if (timeline == null || timeline.resultNotifiedAt() == null) return null;
            C02StaffDecisionPersistence.StoredDecision stored=decisionPersistence.finalDecision(caseId);
            return stored == null ? null : new CustomerDecision(stored.outcome(),stored.message());
        }
        StaffDecisionResult inMemory=staffDecisionsByCase.get(caseId);
        return inMemory == null ? null : new CustomerDecision(inMemory.outcome().name(),inMemory.customerMessage());
    }
    /** The controller enforces STAFF role; this never records a final staff decision. */
    public synchronized StaffPrecheckResult staffReview(long caseId) { return reviewFor(staffDraft(caseId)); }
    /** Staff-only caller obtains the same current revision snapshot used for a final manual decision. */
    public synchronized StaffCaseOverview staffCaseOverview(long caseId) {
        Draft draft=staffDraft(caseId);
        ServiceCaseView latest=casePort.get(draft.customerId,caseId); requireRevision(draft,latest.revision()); draft.view=latest;
        StaffDecisionResult decision=staffDecisionsByCase.get(caseId);
        if (decision == null && decisionPersistence != null) {
            C02StaffDecisionPersistence.StoredDecision stored=decisionPersistence.finalDecision(caseId);
            if (stored != null) decision=fromStoredDecision(stored);
        }
        List<DocumentPort.DocumentMetadata> files=documents.findByCase(caseId);
        StaffPrecheckResult precheck=reviewFor(draft);
        return new StaffCaseOverview(latest, requiredDocuments(draft.intake), files, precheck, intakeView(draft.intake), staffSummary(draft, files, precheck), decision);
    }
    /**
     * Records a staff outcome only after checking the CasePort's current revision and the
     * precheck generated for that exact revision.  It does not approve a payout and does
     * records the C-02 Case outcome through the dedicated persistence transaction when
     * persistence is enabled; the lightweight fallback remains in-memory for existing fixtures.
     */
    public synchronized StaffDecisionResult recordStaffDecision(StaffDecisionCommand command) {
        StaffDecisionResult replay=staffDecisionsByRequest.get(command.requestId());
        if(replay!=null) {
            if(replay.caseId()!=command.caseId() || replay.staffId()!=command.staffId() || replay.revision()!=command.expectedRevision() || replay.outcome()!=command.outcome() || !java.util.Objects.equals(replay.customerMessage(),command.customerMessage()))
                throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused for another C-02 staff decision.");
            return replay;
        }
        if (decisionPersistence != null) {
            C02StaffDecisionPersistence.StoredDecision persisted=decisionPersistence.replay(command.requestId());
            if (persisted != null) {
                if (persisted.caseId()!=command.caseId() || persisted.staffId()!=command.staffId() || persisted.revision()!=command.expectedRevision()
                        || !persisted.outcome().equals(command.outcome().name()) || !persisted.message().equals(notificationMessage(command.outcome(),command.customerMessage())))
                    throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused for another C-02 staff decision.");
                StaffDecisionResult result=fromStoredDecision(persisted);
                staffDecisionsByRequest.put(command.requestId(),result);
                staffDecisionsByCase.put(command.caseId(),result);
                return result;
            }
        }
        Draft draft=staffDraft(command.caseId());
        ServiceCaseView latest=casePort.get(draft.customerId,command.caseId()); requireRevision(draft,command.expectedRevision());
        if(latest.revision()!=command.expectedRevision()) throw new DomainException("VERSION_CONFLICT","Case revision is stale.",true);
        if(latest.caseStatus()!=com.bankingax.core.caseport.CaseStatus.SUBMITTED && latest.caseStatus()!=com.bankingax.core.caseport.CaseStatus.IN_REVIEW) throw new DomainException("INVALID_STATE","Only a submitted C-02 case can receive a staff decision.");
        StaffPrecheckResult precheck=reviewResults.get(new ReviewKey(command.caseId(),command.expectedRevision()));
        if((precheck==null || precheck.finalStaffDecision()) && (decisionPersistence==null || !decisionPersistence.hasCurrentPrecheck(command.caseId(),command.expectedRevision())))
            throw new DomainException("STAFF_PRECHECK_REQUIRED","A current C-02 staff precheck is required before final review.");
        StaffDecisionResult existing=staffDecisionsByCase.get(command.caseId());
        if(existing!=null) throw new DomainException("STAFF_DECISION_ALREADY_RECORDED","A final staff decision is already recorded for this C-02 case.");
        String proposedCaseStatus=switch(command.outcome()) {
            case PROCEED -> "REVIEW_PASSED";
            case REQUIREMENT_FAILED -> "REVIEW_REJECTED_REQUIREMENT";
            case DOCUMENT_INSUFFICIENT -> "REVIEW_REJECTED_DOCUMENT";
        };
        String message=notificationMessage(command.outcome(),command.customerMessage());
        CustomerNotification notification;
        if (decisionPersistence != null) {
            C02StaffDecisionPersistence.StoredDecision stored=decisionPersistence.persist(command.staffId(), command.caseId(), command.expectedRevision(), command.requestId(), command.outcome().name(), message, proposedCaseStatus);
            draft.view=casePort.get(draft.customerId,command.caseId());
            notification=new CustomerNotification(stored.notificationId(),stored.caseId(),stored.revision(),stored.outcome(),stored.message(),false);
        } else notification=new CustomerNotification("c02-web-"+command.caseId()+"-"+command.expectedRevision(),command.caseId(),command.expectedRevision(),command.outcome().name(),message,false);
        StaffDecisionResult result=new StaffDecisionResult(command.caseId(),latest.workItemId(),draft.customerId,command.staffId(),command.expectedRevision(),command.outcome(),latest.caseStatus().name(),proposedCaseStatus,command.customerMessage(),notification);
        staffDecisionsByRequest.put(command.requestId(),result); staffDecisionsByCase.put(command.caseId(),result);
        notificationsByCustomer.computeIfAbsent(draft.customerId,ignored->new ArrayList<>()).add(notification);
        return result;
    }
    public synchronized List<CustomerNotification> customerNotifications(long customerId) {
        if (decisionPersistence != null) return decisionPersistence.notifications(customerId).stream().map(row ->
            new CustomerNotification(row.dedupKey(),row.caseId(),revisionFromDedup(row.dedupKey()),outcomeFromType(row.type()),row.content(),false)).toList();
        return List.copyOf(notificationsByCustomer.getOrDefault(customerId,List.of()));
    }
    public synchronized StaffPrecheckResult runStaffPrecheck(long caseId,int expectedRevision,String requestId) {
        Draft draft=staffDraft(caseId); requireRevision(draft,expectedRevision);
        if(draft.view.caseStatus()!=com.bankingax.core.caseport.CaseStatus.SUBMITTED) throw new DomainException("INVALID_STATE","Only submitted cases can be prechecked.");
        return runStaffPrecheckInternal(draft,requestId);
    }
    private StaffPrecheckResult runStaffPrecheckInternal(Draft draft,String requestId) {
        PrecheckRequest previous=prechecks.get(requestId);
        if(previous!=null) { if(previous.caseId()!=draft.view.caseId() || previous.revision()!=draft.view.revision()) throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused for another staff precheck."); return previous.result(); }
        try { dispatchStaffCheck(draft,requestId); }
        catch(RuntimeException exception) {
            draft.view=casePort.recordCheckResult(draft.view.caseId(),draft.view.revision(),ReviewStatus.FAILED_NEEDS_STAFF);
            reviewResults.put(new ReviewKey(draft.view.caseId(),draft.view.revision()),new StaffPrecheckResult(draft.view.caseId(),draft.view.workItemId(),draft.view.revision(),"FAILED","FAILED_NEEDS_STAFF",List.of(),precheckFailure(draft.view.caseId(),exception),false));
        }
        StaffPrecheckResult result=reviewFor(draft); prechecks.put(requestId,new PrecheckRequest(draft.view.caseId(),draft.view.revision(),result)); return result;
    }
    private StaffPrecheckResult reviewFor(Draft draft) {
        StaffPrecheckResult result=reviewResults.get(new ReviewKey(draft.view.caseId(),draft.view.revision()));
        if (result == null && reviewPersistence != null) {
            C02AgentReviewPersistence.StoredReview stored=reviewPersistence.find(draft.view.caseId(),draft.view.revision());
            if (stored != null) {
                List<Map<String,Object>> items=stored.items().stream().map(item -> Map.<String,Object>of("source",item.source(),"category",item.category(),"result",item.result(),"message",item.message()==null?"":item.message(),"documentFileId",item.documentFileId()==null?0L:item.documentFileId())).toList();
                result=new StaffPrecheckResult(draft.view.caseId(),draft.view.workItemId(),draft.view.revision(),"SUCCEEDED",draft.view.reviewStatus()==null?null:draft.view.reviewStatus().name(),items,null,false);
            }
        }
        if(result==null) result=new StaffPrecheckResult(draft.view.caseId(),draft.view.workItemId(),draft.view.revision(),"NOT_RUN",draft.view.reviewStatus()==null?null:draft.view.reviewStatus().name(),List.of(),null,false);
        if(ocrPersistence!=null) { List<Map<String,Object>> merged=new ArrayList<>(result.reviewItems()); merged.addAll(ocrPersistence.reviewItems(draft.view.caseId())); result=new StaffPrecheckResult(result.caseId(),result.workItemId(),result.revision(),result.agentRunStatus(),result.reviewStatus(),List.copyOf(merged),result.error(),result.finalStaffDecision()); }
        return result;
    }
    private static StaffReviewSummary staffSummary(Draft draft,List<DocumentPort.DocumentMetadata> files,StaffPrecheckResult precheck) {
        List<DocumentSlot> slots=requiredDocuments(draft.intake); Map<String,DocumentPort.DocumentMetadata> latest=new HashMap<>();
        for (DocumentPort.DocumentMetadata file:files) if(file.caseRevision()<=draft.view.revision() && (latest.get(file.documentSlotKey())==null || latest.get(file.documentSlotKey()).version()<file.version())) latest.put(file.documentSlotKey(),file);
        long required=slots.stream().filter(s->s.requirement()==Requirement.REQUIRED).count(); long uploaded=slots.stream().filter(s->s.requirement()==Requirement.REQUIRED&&latest.containsKey(s.key())).count();
        long visit=slots.stream().filter(s->s.requirement()==Requirement.VISIT_ONLY).count(); long absent=draft.intake.heirs().stream().filter(h->h.visitStatus()!=VisitStatus.ATTENDING).count();
        List<Map<String,Object>> items=precheck.reviewItems(); long auto=items.stream().filter(i->"AUTO_MATCHED".equals(i.get("result"))).count(); long mismatch=items.stream().filter(i->"MISMATCH".equals(i.get("result"))).count();
        return new StaffReviewSummary(draft.intake.heirs().size(),absent,required,uploaded,required-uploaded,visit,auto,mismatch,"NOT_RUN".equals(precheck.agentRunStatus()),true);
    }
    private static String notificationMessage(StaffOutcome outcome,String customerMessage) {
        if(customerMessage!=null && !customerMessage.isBlank()) return customerMessage;
        return switch(outcome) {
            case PROCEED -> "직원 사전 검토가 완료되었습니다. 방문 예약은 별도 단계에서 진행됩니다.";
            case REQUIREMENT_FAILED -> "접수 요건을 충족하지 않아 직원 검토 결과가 등록되었습니다.";
            case DOCUMENT_INSUFFICIENT -> "필수 서류 보완이 필요합니다. 안내된 서류를 확인해 주세요.";
        };
    }
    /** Submit evidence comes only from server-owned document metadata, never browser status hints. */
    private Map<String,DocumentStatus> serverDocumentStatuses(Draft draft) {
        // The no-storage InMemory pair is retained solely for legacy unit fixtures.
        // Every Spring runtime wiring supplies C02FileStorage, so browser hints never
        // become submit evidence in the running application.
        if (files==null && documents instanceof InMemoryDocumentPort) return Map.copyOf(draft.documentStatuses);
        Map<String,DocumentPort.DocumentMetadata> latest=new HashMap<>();
        for (DocumentPort.DocumentMetadata item : documents.findByCase(draft.view.caseId())) {
            boolean required=requiredDocuments(draft.intake).stream().anyMatch(slot -> slot.key().equals(item.documentSlotKey()) && slot.documentTypeCode().equals(item.documentType()));
            if (item.caseId()!=draft.view.caseId() || !java.util.Objects.equals(item.uploadedBy(),draft.customerId) || !required) continue;
            // A metadata-only registration cannot count as an upload. The opaque
            // server storage key must resolve before it becomes submit evidence.
            if (files!=null) try (java.io.InputStream ignored=files.open(item.storageReference())) { }
            catch (RuntimeException | java.io.IOException missing) { continue; }
            DocumentPort.DocumentMetadata previous=latest.get(item.documentSlotKey());
            if(previous==null || item.version()>previous.version()) latest.put(item.documentSlotKey(),item);
        }
        Map<String,DocumentStatus> statuses=new HashMap<>();
        latest.forEach((slot,item)->statuses.put(slot,item.status()));
        return statuses;
    }
    private Map<String,Object> snapshot(ServiceCaseView view,Draft draft) {
        List<Map<String,Object>> heirs=draft.intake.heirs().stream().map(h -> Map.<String,Object>of("personRef",h.personRef(),"visitStatus",h.visitStatus().name(),"delegationMethod",h.delegationMethod().name())).toList();
        Submission assessment=assessSubmission(draft.intake,serverDocumentStatuses(draft));
        return Map.of("caseStatus",view.caseStatus().name(),"reviewStatus",String.valueOf(view.reviewStatus()),"decedent",Map.of("deathDate",draft.intake.decedent().deathDate().toString()),"heirs",heirs,"outOfScope",assessment.outOfScope(),"missingDocuments",assessment.missingDocuments(),"requiredDocuments",documentManifest(draft),"ocrExtractions",ocrPersistence==null?List.of():ocrPersistence.snapshot(view.caseId()));
    }
    /** Sends only Spring-derived document metadata to the internal Staff Agent. */
    private List<Map<String,Object>> documentManifest(Draft draft) {
        Map<String,DocumentPort.DocumentMetadata> latest=new HashMap<>();
        for (DocumentPort.DocumentMetadata item : documents.findByCase(draft.view.caseId())) {
            DocumentPort.DocumentMetadata previous=latest.get(item.documentSlotKey());
            if (previous==null || item.version()>previous.version()) latest.put(item.documentSlotKey(),item);
        }
        Map<String,DocumentStatus> statuses=serverDocumentStatuses(draft);
        List<Map<String,Object>> manifest=new ArrayList<>();
        for (DocumentSlot slot : requiredDocuments(draft.intake)) {
            DocumentPort.DocumentMetadata item=latest.get(slot.key());
            Map<String,Object> row=new HashMap<>();
            row.put("slot",slot.key()); row.put("requirement",slot.requirement().name());
            row.put("scope",slot.personRef()==null ? "COMMON" : "HEIR");
            if (slot.personRef()!=null) row.put("personRef",slot.personRef());
            row.put("status",statuses.getOrDefault(slot.key(),DocumentStatus.NOT_SUBMITTED).name());
            if (item!=null) { row.put("documentFileId",item.documentFileId()); row.put("documentVersion",item.version()); row.put("caseRevision",item.caseRevision()); }
            manifest.add(Map.copyOf(row));
        }
        return List.copyOf(manifest);
    }
    private Draft owned(long customerId,long caseId) {
        Draft draft=drafts.get(caseId);
        if (draft == null) draft=restore(customerId,caseId);
        if(draft==null) throw new DomainException("NOT_FOUND","C-02 draft was not found.");
        if(draft.customerId!=customerId) throw new DomainException("FORBIDDEN","Case ownership does not match.");
        return draft;
    }
    private Draft staffDraft(long caseId) {
        Draft draft=drafts.get(caseId);
        if (draft == null && decisionPersistence != null) {
            Long customerId=decisionPersistence.customerId(caseId);
            if (customerId != null) draft=restore(customerId,caseId);
        }
        if(draft==null) throw new DomainException("NOT_FOUND","C-02 case was not found.");
        return draft;
    }
    @SuppressWarnings("unchecked")
    private Draft restore(long customerId,long caseId) {
        ServiceCaseView view=casePort.get(customerId,caseId);
        Object value=view.application().get("c02");
        if (value instanceof Intake intake) { Draft restored=new Draft(customerId,view,intake,Map.of(),null); drafts.put(caseId,restored); return restored; }
        if (!(value instanceof Map<?,?> raw)) throw new DomainException("CASE_SNAPSHOT_INVALID","C-02 application snapshot is unavailable.");
        Map<String,Object> c02=(Map<String,Object>)raw;
        Object decedentValue=c02.get("decedent"); Object heirsValue=c02.get("heirs");
        if (!(decedentValue instanceof Map<?,?> decedentRaw) || !(heirsValue instanceof List<?> heirsRaw))
            throw new DomainException("CASE_SNAPSHOT_INVALID","C-02 application snapshot is invalid.");
        Map<String,Object> decedent=(Map<String,Object>)decedentRaw;
        DecedentInput input=new DecedentInput(text(decedent,"name"), date(decedent,"birthDate"), date(decedent,"deathDate"), number(decedent,"depositTotal"));
        List<HeirInput> heirs=new ArrayList<>();
        for (Object item : heirsRaw) {
            if (!(item instanceof Map<?,?> heirRaw)) throw new DomainException("CASE_SNAPSHOT_INVALID","C-02 heir snapshot is invalid.");
            Map<String,Object> heir=(Map<String,Object>)heirRaw;
            heirs.add(new HeirInput(text(heir,"personRef"),text(heir,"name"),date(heir,"birthDate"),text(heir,"relationship"),
                bool(heir,"applicant"),bool(heir,"higherOrderOrSubrogation"),VisitStatus.valueOf(text(heir,"visitStatus")),
                DelegationMethod.valueOf(text(heir,"delegationMethod")),nullableText(heir,"delegationReceiptNo")));
        }
        Draft restored=new Draft(customerId,view,new Intake(input,List.copyOf(heirs)),Map.of(),null);
        drafts.put(caseId,restored);
        return restored;
    }
    private static String text(Map<String,Object> value,String key) { Object item=value.get(key); if(item==null) throw new DomainException("CASE_SNAPSHOT_INVALID","C-02 application snapshot is invalid."); return String.valueOf(item); }
    private static String nullableText(Map<String,Object> value,String key) { Object item=value.get(key); return item==null?null:String.valueOf(item); }
    private static long number(Map<String,Object> value,String key) { Object item=value.get(key); return item instanceof Number n?n.longValue():Long.parseLong(String.valueOf(item)); }
    private static boolean bool(Map<String,Object> value,String key) { Object item=value.get(key); return item instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(item)); }
    private static LocalDate date(Map<String,Object> value,String key) { Object item=value.get(key); return item==null?null:LocalDate.parse(String.valueOf(item)); }
    private static int revisionFromDedup(String key) { int dash=key.lastIndexOf('-'); return dash<0?0:Integer.parseInt(key.substring(dash+1)); }
    private static String outcomeFromType(String type) { return C02StaffDecisionPersistence.outcomeOf(type); }
    /**
     * NOTI CASE_RECEIVED (spec 11-3). The Case is already committed as SUBMITTED, so a notification
     * failure is logged instead of reporting the submission as failed; the dedup key keeps replays to one row.
     */
    private void notifyReceived(long customerId,ServiceCaseView submitted) {
        if(notificationWriter==null) return;
        try {
            notificationWriter.store(customerId,submitted.caseId(),submitted.workItemId(),"CASE_RECEIVED","상속예금 사전접수가 완료됐어요",
                "직원이 제출 서류를 검토한 뒤 결과를 알려 드려요. 방문 예약은 직원 검토 결과가 진행 가능일 때 열려요.","c02-received-"+submitted.caseId());
        } catch (RuntimeException exception) {
            System.getLogger(C02IntakeService.class.getName()).log(System.Logger.Level.WARNING,"C-02 CASE_RECEIVED notification was not stored for case "+submitted.caseId(),exception);
        }
    }
    private static StaffDecisionResult fromStoredDecision(C02StaffDecisionPersistence.StoredDecision stored) {
        StaffOutcome outcome=StaffOutcome.valueOf(stored.outcome());
        CustomerNotification notification=new CustomerNotification(stored.notificationId(),stored.caseId(),stored.revision(),stored.outcome(),stored.message(),false);
        return new StaffDecisionResult(stored.caseId(),stored.workItemId(),stored.customerId(),stored.staffId(),stored.revision(),outcome,
            "SUBMITTED",stored.targetStatus(),stored.message(),notification);
    }
    private static void requireRevision(Draft draft,int revision) { if(draft.view.revision()!=revision) throw new DomainException("VERSION_CONFLICT","Case revision is stale.",true); }
    private static DocumentSlot slot(String code, Requirement requirement, String personRef, String note) { return new DocumentSlot(code, requirement, personRef, note); }
    private static String documentType(String slotKey) { int delimiter=slotKey.indexOf(':'); return delimiter < 0 ? slotKey : slotKey.substring(0,delimiter); }

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
    /** This is an Agent precheck result, never a staff_review final decision. */
    public record StaffPrecheckResult(long caseId,long workItemId,int revision,String agentRunStatus,String reviewStatus,List<Map<String,Object>> reviewItems,Map<String,Object> error,boolean finalStaffDecision) {}
    /** Customer-entered statement only; it is never bank-verified account or entitlement data. */
    public record StaffCaseOverview(ServiceCaseView caseView,List<DocumentSlot> requiredDocuments,List<DocumentPort.DocumentMetadata> documents,StaffPrecheckResult precheck,CaseIntakeView intake,StaffReviewSummary summary,StaffDecisionResult finalDecision) {}
    public record StaffReviewSummary(long heirCount,long absentHeirCount,long requiredUploadCount,long uploadedRequiredCount,long missingRequiredCount,long visitOnlyCount,long autoMatchedCount,long mismatchCount,boolean precheckNotRun,boolean manualVerificationRequired) {}
    public record CustomerCaseSnapshot(long caseId,long workItemId,int revision,String caseStatus,String reviewStatus,
            boolean visitRequired,List<DocumentSlot> requiredDocuments,List<DocumentPort.DocumentMetadata> documents,
            StaffPrecheckResult precheck,CaseIntakeView intake,C02StaffDecisionPersistence.Timeline timeline,CustomerDecision finalDecision) {}
    public record CaseIntakeView(String decedentName,LocalDate decedentBirthDate,LocalDate deathDate,long customerStatedDepositTotal,List<HeirView> heirs) {}
    public record HeirView(String personRef,String name,LocalDate birthDate,String relationship,boolean applicant,String visitStatus,String delegationMethod) {}
    public record CustomerDecision(String outcome,String customerMessage) {}
    public record StaffDecisionCommand(long staffId,long caseId,int expectedRevision,String requestId,StaffOutcome outcome,String customerMessage) {}
    /** The final status is persisted when C-02 review persistence is enabled; otherwise this is the in-memory fallback proposal. */
    public record StaffDecisionResult(long caseId,long workItemId,long customerId,long staffId,int revision,StaffOutcome outcome,String currentCaseStatus,String proposedCaseStatus,String customerMessage,CustomerNotification notification) {}
    public record CustomerNotification(String notificationId,long caseId,int revision,String outcome,String message,boolean externalDeliveryAttempted) {}
    public record DocumentUpdate(DocumentPort.DocumentMetadata document,int revision,String reviewStatus,StaffPrecheckResult precheck) {}
    public record FileDownload(DocumentPort.DocumentMetadata metadata,java.io.InputStream content) {}
    public record Fingerprint(long customerId, long workItemId, Intake intake, Map<String, DocumentStatus> documentStatuses) {}
    private record PreparedRequest(Fingerprint fingerprint, Prepared prepared) {}
    private record SubmittedRequest(C02SubmitCommand command, SubmissionResult result) {}
    private record PrecheckRequest(long caseId,int revision,StaffPrecheckResult result) {}
    private record ReviewKey(long caseId,int revision) {}
    private static final class Draft { final long customerId; Intake intake; final Map<String,DocumentStatus> documentStatuses; final String phone; ServiceCaseView view; boolean consented; boolean mockSignatureCompleted;
        Draft(long customerId,ServiceCaseView view,Intake intake,Map<String,DocumentStatus> documentStatuses,String phone){this.customerId=customerId;this.view=view;this.intake=intake;this.documentStatuses=documentStatuses;this.phone=phone;}
        C02Draft view(){return new C02Draft(view.caseId(),view.workItemId(),view.revision(),view.caseStatus().name(),view.reviewStatus()==null?null:view.reviewStatus().name(),view.visitRequired());}
    }
    public enum VisitStatus { UNKNOWN, ATTENDING, ABSENT, UNCONFIRMED_ABSENT }
    public enum DelegationMethod { NONE, DOCUMENT, RECEIPT }
    public enum Requirement { REQUIRED, CONDITIONAL, VISIT_ONLY }
    public enum DocumentStatus { NOT_SUBMITTED, UPLOADED, VALIDATION_FAILED, SYSTEM_VALIDATED, HUMAN_VERIFIED, NEEDS_SUPPLEMENT }
    public enum StaffOutcome { PROCEED, REQUIREMENT_FAILED, DOCUMENT_INSUFFICIENT }
}
