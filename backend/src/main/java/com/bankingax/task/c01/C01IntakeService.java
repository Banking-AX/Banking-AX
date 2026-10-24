package com.bankingax.task.c01;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.CasePort;
import com.bankingax.core.caseport.CreateCaseCommand;
import com.bankingax.core.caseport.ServiceCaseView;
import com.bankingax.core.verification.*;
import com.bankingax.core.workflow.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/** Fake-backed C-01 vertical slice. It preserves server-side consent/signature flags and binding checks. */
@Service
public class C01IntakeService {
    private final TaskHandler tasks; private final CasePort cases; private final VerificationPort verification;
    private final DebtAdjustmentLoanPort loans; private final DebtAdjustmentRuleValidator rules;
    private final Map<Long, Draft> drafts = new HashMap<>(); private final Map<String, C01Submission> submissions = new HashMap<>();
    private final Map<String, StartResult> starts = new HashMap<>();
    public C01IntakeService(TaskHandler tasks, CasePort cases, VerificationPort verification, DebtAdjustmentLoanPort loans, DebtAdjustmentRuleValidator rules) {
        this.tasks=tasks; this.cases=cases; this.verification=verification; this.loans=loans; this.rules=rules;
    }
    public synchronized C01Draft start(C01StartCommand command) {
        StartFingerprint fingerprint = StartFingerprint.from(command);
        StartResult previous = starts.get(command.requestId());
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint))
                throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another C-01 start request.");
            return previous.draft();
        }
        C01LoanProfile loan = loans.findOwned(command.customerId(), command.loanId());
        WorkItemView work = tasks.start(new StartTaskCommand(command.customerId(), command.workflowId(), TaskCode.DEBT_ADJUSTMENT, command.expectedWorkflowVersion(), command.requestId()));
        ServiceCaseView view = cases.create(new CreateCaseCommand(command.customerId(), work.workItemId(), TaskCode.DEBT_ADJUSTMENT, loan.loanId(), Map.of()));
        Draft draft = new Draft(command.customerId(), work, view, loan, command.registeredPhone()); drafts.put(view.caseId(), draft);
        C01Draft result = draft.view(rules.evaluate(loan, LocalDate.now()));
        starts.put(command.requestId(), new StartResult(fingerprint, result));
        return result;
    }
    public synchronized C01Draft saveIntake(long customerId, long caseId, int expectedRevision, String reason, String situation, long monthlyRepaymentCapacity, boolean requiredDocumentsSatisfied) {
        Draft d=owned(customerId, caseId); requireRevision(d, expectedRevision); if (blank(reason)||blank(situation)||monthlyRepaymentCapacity<0) throw new DomainException("INTAKE_REQUIRED","신청 사유, 현재 상황, 변제능력은 필수입니다.");
        d.reason=reason; d.situation=situation; d.monthlyRepaymentCapacity=monthlyRepaymentCapacity; d.requiredDocumentsSatisfied=requiredDocumentsSatisfied; return d.view(rules.evaluate(d.loan, LocalDate.now()));
    }
    public synchronized C01Draft recordConsentAndMockSignature(long customerId,long caseId,int revision,boolean consented,boolean mockSignatureCompleted) {
        Draft d=owned(customerId,caseId); requireRevision(d,revision); d.consented=consented; d.mockSignatureCompleted=mockSignatureCompleted; return d.view(rules.evaluate(d.loan, LocalDate.now()));
    }
    public synchronized VerificationIssue issueOtp(long customerId,long caseId,int revision) {
        Draft d=owned(customerId,caseId); requireRevision(d,revision); if(!d.consented||!d.mockSignatureCompleted) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","필수 동의와 모의 서명이 필요합니다.");
        return verification.issue(new VerificationIssueCommand(customerId,d.work.workItemId(),VerificationAction.C01_SUBMIT,revision,d.phone));
    }
    public VerificationBinding verifyOtp(long customerId,String challengeId,String otp) { return verification.verify(new VerificationVerifyCommand(customerId,challengeId,otp)); }
    public synchronized C01Submission submit(C01SubmitCommand command) {
        C01Submission previous=submissions.get(command.requestId()); if(previous!=null) {
            if(previous.caseId()!=command.caseId()||previous.revision()!=command.expectedRevision()||previous.customerId()!=command.customerId()) throw new DomainException("DUPLICATE_REQUEST_CONFLICT","requestId was reused for another submission.");
            return previous;
        }
        Draft d=owned(command.customerId(),command.caseId()); requireRevision(d,command.expectedRevision());
        C01RuleEvaluation evaluation=rules.evaluate(d.loan,LocalDate.now());
        if(!evaluation.submissionAllowed()) throw new DomainException("C01_RULE_BLOCKED","C-01 system rule prevents submission.");
        if(blank(d.reason)||blank(d.situation)||d.monthlyRepaymentCapacity<0) throw new DomainException("INTAKE_REQUIRED","필수 진술이 없습니다.");
        if(!d.requiredDocumentsSatisfied) throw new DomainException("REQUIRED_DOCUMENT_MISSING","문서 포트 Fake에서 필수 슬롯이 충족되지 않았습니다.");
        if(!d.consented||!d.mockSignatureCompleted) throw new DomainException("CONSENT_OR_SIGNATURE_REQUIRED","필수 동의와 모의 서명이 필요합니다.");
        verification.consume(new VerificationConsumeCommand(command.bindingId(),command.customerId(),d.work.workItemId(),VerificationAction.C01_SUBMIT,command.expectedRevision()));
        ServiceCaseView submitted=cases.submit(command.customerId(),command.caseId(),command.expectedRevision(),command.requestId()); d.view=submitted;
        C01Submission result=new C01Submission(command.customerId(),submitted.caseId(),submitted.workItemId(),submitted.revision(),submitted.caseStatus().name(),submitted.reviewStatus().name(),false,evaluation.items()); submissions.put(command.requestId(),result); return result;
    }
    public synchronized C01Draft revise(long customerId,long caseId,int expectedRevision,String reason) { Draft d=owned(customerId,caseId); requireRevision(d,expectedRevision); d.view=cases.revise(customerId,caseId,expectedRevision,Map.of("reason",reason),"CUSTOMER_UPDATE"); return d.view(rules.evaluate(d.loan,LocalDate.now())); }
    public synchronized Map<String,Object> staffSnapshot(long customerId,long caseId) { Draft d=owned(customerId,caseId); Map<String,Object> snapshot=new HashMap<>(); snapshot.put("caseStatus",d.view.caseStatus().name()); snapshot.put("reviewStatus",reviewStatus(d.view)); snapshot.put("ruleResults",rules.evaluate(d.loan,LocalDate.now()).items()); snapshot.put("reason",d.reason); snapshot.put("currentSituation",d.situation); snapshot.put("monthlyRepaymentCapacity",d.monthlyRepaymentCapacity); return snapshot; }
    private Draft owned(long customerId,long caseId){ Draft d=drafts.get(caseId); if(d==null) throw new DomainException("NOT_FOUND","C-01 draft was not found."); if(d.customerId!=customerId) throw new DomainException("FORBIDDEN","Case ownership does not match."); return d; }
    private static void requireRevision(Draft d,int revision){if(d.view.revision()!=revision) throw new DomainException("VERSION_CONFLICT","Case revision is stale.",true);}
    private static boolean blank(String text){return text==null||text.isBlank();}
    public record C01StartCommand(long customerId,long workflowId,int expectedWorkflowVersion,String requestId,long loanId,String registeredPhone) { }
    public record C01SubmitCommand(long customerId,long caseId,int expectedRevision,String requestId,String bindingId) { }
    public record C01Draft(long caseId,long workItemId,int revision,String caseStatus,String reviewStatus,boolean visitRequired,C01RuleEvaluation ruleEvaluation) { }
    public record C01Submission(long customerId,long caseId,long workItemId,int revision,String caseStatus,String reviewStatus,boolean visitReservationCreated,List<C01RuleItem> ruleItems) { }
    private record StartFingerprint(long customerId, long workflowId, int expectedWorkflowVersion, long loanId, String registeredPhone) {
        static StartFingerprint from(C01StartCommand command) { return new StartFingerprint(command.customerId(), command.workflowId(), command.expectedWorkflowVersion(), command.loanId(), command.registeredPhone()); }
    }
    private record StartResult(StartFingerprint fingerprint, C01Draft draft) { }
    private static final class Draft { final long customerId; final WorkItemView work; final C01LoanProfile loan; final String phone; ServiceCaseView view; String reason; String situation; long monthlyRepaymentCapacity=-1; boolean requiredDocumentsSatisfied; boolean consented; boolean mockSignatureCompleted;
        Draft(long customerId,WorkItemView work,ServiceCaseView view,C01LoanProfile loan,String phone){this.customerId=customerId;this.work=work;this.view=view;this.loan=loan;this.phone=phone;}
        C01Draft view(C01RuleEvaluation evaluation){return new C01Draft(view.caseId(),work.workItemId(),view.revision(),view.caseStatus().name(),reviewStatus(view),false,evaluation);}
    }
    private static String reviewStatus(ServiceCaseView view) { return view.reviewStatus() == null ? null : view.reviewStatus().name(); }
}
