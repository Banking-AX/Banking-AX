package com.bankingax.task.c02;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.InternalGatewayAuthenticator;
import com.bankingax.core.verification.VerificationBinding;
import com.bankingax.core.verification.VerificationIssue;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Internal Tool boundary for C-02 preparation; it never submits or approves an inheritance claim. */
@RestController
@RequestMapping("/api/v1/c02")
public class C02Controller {
    private final C02IntakeService service; private final InternalGatewayAuthenticator authenticator; private final OcrPersistence ocr;
    public C02Controller(C02IntakeService service, InternalGatewayAuthenticator authenticator) { this(service,authenticator,(OcrPersistence)null); }
    @Autowired public C02Controller(C02IntakeService service, InternalGatewayAuthenticator authenticator,ObjectProvider<OcrPersistence> ocr) { this(service,authenticator,ocr.getIfAvailable()); }
    private C02Controller(C02IntakeService service, InternalGatewayAuthenticator authenticator,OcrPersistence ocr) { this.service=service; this.authenticator=authenticator; this.ocr=ocr; }
    @PostMapping("/prepare") public ApiEnvelope<C02IntakeService.Prepared> prepare(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role, @RequestHeader(value="X-Tool-Gateway-Token", required=false, defaultValue="") String token,
            @RequestBody PrepareBody body) {
        authenticateCustomer(actorId, role, token, body.customerId());
        var intake = new C02IntakeService.Intake(new C02IntakeService.DecedentInput(body.decedent().name(), body.decedent().birthDate(), body.decedent().deathDate(), body.decedent().depositTotal()),
            body.heirs().stream().map(h -> new C02IntakeService.HeirInput(h.personRef(),h.name(),h.birthDate(),h.relationship(),h.applicant(),h.higherOrderOrSubrogation(),h.visitStatus(),h.delegationMethod(),h.delegationReceiptNo())).toList());
        var data=service.prepare(body.customerId(),body.workItemId(),body.requestId(),intake,body.documentStatuses()==null ? Map.of() : body.documentStatuses(),body.registeredPhone());
        Long caseId=data.caseView()==null ? null : data.caseView().caseId(); Integer revision=data.caseView()==null ? null : data.caseView().revision();
        return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",body.workItemId(),caseId,revision,data);
    }
    @GetMapping("/work-items/{workItemId}/resume") public ApiEnvelope<C02IntakeService.Prepared> resume(@PathVariable long workItemId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); var data=service.resume(customerId,workItemId);
        return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",workItemId,data.caseView().caseId(),data.caseView().revision(),data);
    }
    @PostMapping("/{caseId}/consent") public ApiEnvelope<C02IntakeService.C02Draft> consent(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody ConsentBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.recordConsentAndMockSignature(body.customerId(),caseId,body.revision(),body.consented(),body.mockSignatureCompleted()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/verification/issue") public ApiEnvelope<VerificationIssue> issue(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody RevisionBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.issueOtp(body.customerId(),caseId,body.revision()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",null,caseId,body.revision(),data);
    }
    @PostMapping("/verification/verify") public ApiEnvelope<VerificationBinding> verify(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody VerifyBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.verifyOtp(body.customerId(),body.challengeId(),body.otp()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",null,null,null,data);
    }
    @PostMapping("/{caseId}/submit") public ApiEnvelope<C02IntakeService.SubmissionResult> submit(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody SubmitBody body) {
        authenticateCustomer(actorId,role,token,body.customerId()); var data=service.submit(new C02IntakeService.C02SubmitCommand(body.customerId(),caseId,body.revision(),body.requestId(),body.bindingId())); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/documents/metadata") public ApiEnvelope<C02IntakeService.DocumentUpdate> registerMetadata(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody DocumentMetadataBody body) {
        authenticateCustomer(actorId,role,token,body.customerId());
        var registration=new DocumentPort.DocumentRegistration(caseId,body.revision(),body.documentSlotKey(),body.fileName(),body.mimeType(),body.sizeBytes(),body.storageReference(),body.status());
        var data=service.registerDocument(body.customerId(),caseId,body.revision(),body.requestId(),registration);
        return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",null,caseId,data.revision(),data);
    }
    @PostMapping(value="/{caseId}/documents/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiEnvelope<C02IntakeService.DocumentUpdate> upload(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam int revision,@RequestParam String requestId,@RequestParam String documentSlotKey,@RequestPart MultipartFile file) { authenticateCustomer(actorId,role,token,customerId); var data=service.uploadDocument(customerId,caseId,revision,requestId,documentSlotKey,file); if(ocr!=null) ocr.extract(data.document().documentFileId(),caseId,data.revision(),documentSlotKey.split(":",2)[0],data.document().storageReference()); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",null,caseId,data.revision(),data); }
    @GetMapping("/{caseId}/documents/{documentId}/content") public ResponseEntity<org.springframework.core.io.InputStreamResource> download(@PathVariable long caseId,@PathVariable long documentId,@RequestHeader("X-Actor-Id") long actorId,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam(required=false) Long customerId) { C02IntakeService.FileDownload data; if(role==ActorRole.CUSTOMER){if(customerId==null)throw new DomainException("FORBIDDEN","Customer identity is required.");authenticateCustomer(actorId,role,token,customerId);data=service.customerDocument(customerId,caseId,documentId);}else{authenticateStaff(role,token);data=service.staffDocument(caseId,documentId);} return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.metadata().mimeType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.metadata().fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(new org.springframework.core.io.InputStreamResource(data.content())); }
    @GetMapping("/{caseId}/documents") public ApiEnvelope<List<DocumentPort.DocumentMetadata>> documents(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); var data=service.documents(customerId,caseId); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",null,caseId,null,data);
    }
    @GetMapping("/{caseId}/customer-ocr") public ApiEnvelope<OcrPersistence.Snapshot> customerOcr(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam int revision,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); if(ocr==null) throw new DomainException("OCR_QUERY_UNAVAILABLE","Persisted OCR results are unavailable.",true);
        int current=service.currentRevisionForCustomer(customerId,caseId); if(current!=revision) throw new DomainException("VERSION_CONFLICT","The requested OCR revision is stale.",true);
        return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",null,caseId,current,ocr.customerSnapshot(caseId,current));
    }
    @GetMapping("/{caseId}/review") public ApiEnvelope<C02IntakeService.StaffPrecheckResult> customerReview(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); var data=service.customerReview(customerId,caseId); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @GetMapping("/{caseId}/customer-snapshot") public ApiEnvelope<C02IntakeService.CustomerCaseSnapshot> customerSnapshot(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); var data=service.customerCaseSnapshot(customerId,caseId);
        return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @PostMapping("/{caseId}/staff-precheck") public ApiEnvelope<C02IntakeService.StaffPrecheckResult> precheck(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody StaffPrecheckBody body) {
        authenticateStaff(role,token); var data=service.runStaffPrecheck(caseId,body.revision(),body.requestId()); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @GetMapping("/{caseId}/staff-review") public ApiEnvelope<C02IntakeService.StaffPrecheckResult> staffReview(@PathVariable long caseId,@RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam String requestId) {
        authenticateStaff(role,token); var data=service.staffReview(caseId); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @GetMapping("/{caseId}/staff-case") public ApiEnvelope<C02IntakeService.StaffCaseOverview> staffCase(@PathVariable long caseId,@RequestHeader("X-Actor-Role") ActorRole role,
            @RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam String requestId) {
        authenticateStaff(role,token); var data=service.staffCaseOverview(caseId); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",data.caseView().workItemId(),caseId,data.caseView().revision(),data);
    }
    @GetMapping("/{caseId}/staff-ocr") public ApiEnvelope<OcrPersistence.Snapshot> staffOcr(@PathVariable long caseId,@RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam int revision,@RequestParam String requestId) {
        authenticateStaff(role,token); if(ocr==null) throw new DomainException("OCR_QUERY_UNAVAILABLE","Persisted OCR results are unavailable.",true);
        int current=service.currentRevisionForStaff(caseId); if(current!=revision) throw new DomainException("VERSION_CONFLICT","The requested OCR revision is stale.",true);
        return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",null,caseId,current,ocr.currentSnapshot(caseId,current));
    }
    @PostMapping("/{caseId}/staff-decision") public ApiEnvelope<C02IntakeService.StaffDecisionResult> staffDecision(@PathVariable long caseId,@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestBody StaffDecisionBody body) {
        authenticateStaff(role,token); var data=service.recordStaffDecision(new C02IntakeService.StaffDecisionCommand(actorId,caseId,body.revision(),body.requestId(),body.outcome(),body.customerMessage(),body.exceptionReason())); return ApiEnvelope.success(body.requestId(),"INHERITANCE_DEPOSIT",data.workItemId(),caseId,data.revision(),data);
    }
    @GetMapping("/notifications") public ApiEnvelope<List<C02IntakeService.CustomerNotification>> notifications(@RequestHeader("X-Actor-Id") long actorId,
            @RequestHeader("X-Actor-Role") ActorRole role,@RequestHeader(value="X-Tool-Gateway-Token",required=false,defaultValue="") String token,@RequestParam long customerId,@RequestParam String requestId) {
        authenticateCustomer(actorId,role,token,customerId); var data=service.customerNotifications(customerId); return ApiEnvelope.success(requestId,"INHERITANCE_DEPOSIT",null,null,null,data);
    }
    private void authenticateCustomer(long actorId, ActorRole role, String token, long customerId) { authenticator.verify(token); if(role!=ActorRole.CUSTOMER || actorId!=customerId) throw new DomainException("FORBIDDEN","Customer ownership cannot be verified."); }
    private void authenticateStaff(ActorRole role,String token) { authenticator.verify(token); if(role!=ActorRole.STAFF) throw new DomainException("FORBIDDEN","Staff role is required."); }
    public record PrepareBody(long customerId,long workItemId,String requestId,String registeredPhone,DecedentBody decedent,List<HeirBody> heirs,Map<String,C02IntakeService.DocumentStatus> documentStatuses) {}
    public record DecedentBody(String name,LocalDate birthDate,LocalDate deathDate,long depositTotal) {}
    public record HeirBody(String personRef,String name,LocalDate birthDate,String relationship,boolean applicant,boolean higherOrderOrSubrogation,C02IntakeService.VisitStatus visitStatus,C02IntakeService.DelegationMethod delegationMethod,String delegationReceiptNo) {}
    public record ConsentBody(long customerId,int revision,String requestId,boolean consented,boolean mockSignatureCompleted) {}
    public record RevisionBody(long customerId,int revision,String requestId) {}
    public record VerifyBody(long customerId,String requestId,String challengeId,String otp) {}
    public record SubmitBody(long customerId,int revision,String requestId,String bindingId) {}
    public record DocumentMetadataBody(long customerId,int revision,String requestId,String documentSlotKey,String fileName,String mimeType,long sizeBytes,String storageReference,C02IntakeService.DocumentStatus status) {}
    public record StaffPrecheckBody(int revision,String requestId) {}
    public record StaffDecisionBody(int revision,String requestId,C02IntakeService.StaffOutcome outcome,String customerMessage,String exceptionReason) {
        public StaffDecisionBody(int revision,String requestId,C02IntakeService.StaffOutcome outcome,String customerMessage) { this(revision,requestId,outcome,customerMessage,null); }
    }
}
