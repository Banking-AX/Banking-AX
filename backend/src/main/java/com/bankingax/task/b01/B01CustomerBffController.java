package com.bankingax.task.b01;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/customer/b01")
public class B01CustomerBffController {
    private final TaskBffPrincipal principals;
    private final B01DocumentService documents;
    private final B01ProgressService progress;
    private final B01VerificationService verification;
    private final B01LoanListService loans;
    private final B01ExecutionService execution;

    B01CustomerBffController(TaskBffPrincipal principals, B01DocumentService documents,
                             B01ProgressService progress, B01VerificationService verification,
                             B01LoanListService loans, B01ExecutionService execution) {
        this.principals = principals;
        this.documents = documents;
        this.progress = progress;
        this.verification = verification;
        this.loans = loans;
        this.execution = execution;
    }

    @GetMapping(value = "/application-form", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Resource> applicationForm(JwtAuthenticationToken authentication) {
        customer(authentication);
        Resource form = new ClassPathResource("b01/mock-ocr/b01_blank_201903.pdf");
        if (!form.exists()) {
            throw new DomainException("B01_APPLICATION_FORM_UNAVAILABLE",
                    "The B-01 application form is unavailable.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("b01-repayment-order-change-application-201903.pdf")
                        .build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(form);
    }

    @GetMapping("/work-items/{workItemId}/loans")
    public ApiEnvelope<java.util.List<B01LoanListService.LoanListItem>> loans(
            JwtAuthenticationToken authentication, @PathVariable long workItemId) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(null, "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                loans.findOwnedLoans(customerId, workItemId));
    }

    @GetMapping("/work-items/{workItemId}/context")
    public ApiEnvelope<B01ProgressService.ProgressContext> context(
            JwtAuthenticationToken authentication, @PathVariable long workItemId) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(null, "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                progress.context(customerId, workItemId));
    }

    @PutMapping(value = "/work-items/{workItemId}/context", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01ProgressService.ProgressContext> saveContext(
            JwtAuthenticationToken authentication, @PathVariable long workItemId,
            @RequestBody B01ProgressService.SelectionCommand body) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                progress.saveSelection(customerId, workItemId, body));
    }

    @PostMapping(value = "/work-items/{workItemId}/documents/application",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<B01DocumentService.UploadResult> upload(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestParam String requestId,
            @RequestPart("file") MultipartFile file) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role")))
            throw new DomainException("FORBIDDEN", "Only an authenticated customer can upload a B-01 document.");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(requestId, "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                documents.upload(customerId, workItemId, requestId, file));
    }

    @PostMapping(value = "/work-items/{workItemId}/eligibility", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01EligibilityService.ValidationResult> validate(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestBody ValidateRequest body) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role")))
            throw new DomainException("FORBIDDEN", "Only an authenticated customer can validate a B-01 request.");
        if (body.requestId() == null || body.requestId().isBlank() || body.requestId().length() > 90)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 90 characters is required.");
        long customerId = principals.customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                progress.validateAndSave(customerId, workItemId, body.loanId(), body.requestedRepaymentOrder()));
    }

    public record ValidateRequest(String requestId, long loanId, String requestedRepaymentOrder) { }

    @PostMapping(value = "/work-items/{workItemId}/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01VerificationService.ConsentResult> consent(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestBody B01VerificationService.ConsentCommand body) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                verification.consent(customerId, workItemId, body));
    }

    @PostMapping(value = "/work-items/{workItemId}/mock-signature",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiEnvelope<B01VerificationService.SignatureResult> signature(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestParam String requestId,
            @RequestPart("file") MultipartFile file) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(requestId, "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                verification.signature(customerId, workItemId, requestId, file));
    }

    @PostMapping(value = "/work-items/{workItemId}/sms/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01VerificationService.SmsIssue> requestSms(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestBody RequestOnly body) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                verification.requestSms(customerId, workItemId, body.requestId()));
    }

    @PostMapping(value = "/work-items/{workItemId}/sms/verifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01VerificationService.SmsVerification> verifySms(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestBody SmsVerificationRequest body) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                verification.verifySms(customerId, workItemId, body.requestId(), body.challengeId(), body.otp()));
    }

    @GetMapping("/work-items/{workItemId}/verification-status")
    public ApiEnvelope<B01VerificationService.VerificationStatus> status(JwtAuthenticationToken authentication,
            @PathVariable long workItemId) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(null, "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                verification.status(customerId, workItemId));
    }

    @PostMapping(value = "/work-items/{workItemId}/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<B01ExecutionService.ExecutionResult> execute(JwtAuthenticationToken authentication,
            @PathVariable long workItemId, @RequestBody RequestOnly body) {
        long customerId = customer(authentication);
        return ApiEnvelope.success(body.requestId(), "REPAYMENT_ORDER_CHANGE", workItemId, null, null,
                execution.execute(customerId, workItemId, body.requestId()));
    }

    private long customer(JwtAuthenticationToken authentication) {
        if (!"CUSTOMER".equals(authentication.getToken().getClaimAsString("role")))
            throw new DomainException("FORBIDDEN", "Only an authenticated customer can use B-01 verification.");
        return principals.customer(authentication);
    }

    public record RequestOnly(String requestId) { }
    public record SmsVerificationRequest(String requestId, String challengeId, String otp) { }
}
