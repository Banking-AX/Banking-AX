package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class B01ProgressService {
    private static final String TASK_CODE = "REPAYMENT_ORDER_CHANGE";
    private static final String SELECTION_EVENT = "B01_SELECTION_SAVED";
    private static final String SELECTION_REQUEST_PREFIX = "b01-selection:";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final B01EligibilityService eligibility;

    B01ProgressService(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json,
                       B01EligibilityService eligibility) {
        this.jdbc = jdbc.getIfAvailable();
        this.json = json;
        this.eligibility = eligibility;
    }

    @Transactional
    public ProgressContext saveSelection(long customerId, long workItemId, SelectionCommand command) {
        saveSelectionState(customerId, workItemId, command, false);
        return context(customerId, workItemId);
    }

    @Transactional
    public SelectionSaveResult saveSelectionForTool(long customerId, long workItemId, SelectionCommand command) {
        return saveSelectionState(customerId, workItemId, command, true);
    }

    @Transactional(readOnly = true)
    public B01EligibilityService.LoanRuleResult precheckSavedSelection(long customerId, long workItemId) {
        LockedWorkItem item = lock(customerId, workItemId, false, false, true);
        ObjectNode draft = draft(item.draft());
        Long selectedLoanId = positiveLong(draft, "selectedLoanId");
        String requestedOrder = text(draft, "requestedRepaymentOrder");
        if (selectedLoanId == null || requestedOrder == null) {
            throw new DomainException("B01_SELECTION_REQUIRED",
                    "Select a loan and repayment order before eligibility can be checked.");
        }
        return eligibility.precheck(customerId, workItemId, selectedLoanId, requestedOrder);
    }

    private SelectionSaveResult saveSelectionState(long customerId, long workItemId,
            SelectionCommand command, boolean distinguishOnHold) {
        if (command == null) throw new DomainException("INVALID_REQUEST", "Selection details are required.");
        validateRequestId(command.requestId());
        requestedOrder(command.requestedRepaymentOrder());
        LockedWorkItem locked = lock(customerId, workItemId, false, true, distinguishOnHold);
        ObjectNode draft = draft(locked.draft());
        Long selectedLoanId = positiveLong(draft, "selectedLoanId");
        String selectedOrder = text(draft, "requestedRepaymentOrder");
        SelectionReceipt receipt = selectionReceipt(command.requestId());
        if (receipt != null) {
            requireMatchingReceipt(receipt, customerId, workItemId, command);
            return new SelectionSaveResult(workItemId, selectedLoanId, selectedOrder,
                    false, true, locked.version());
        }
        requireOwnedLoan(customerId, command.loanId());
        if (command.requestId().equals(text(draft, "selectionRequestId"))) {
            if (!Objects.equals(selectedLoanId, command.loanId())
                    || !Objects.equals(selectedOrder, command.requestedRepaymentOrder())) {
                throw new DomainException("DUPLICATE_REQUEST_CONFLICT",
                        "The requestId was already used for a different B-01 selection.");
            }
            recordSelection(customerId, workItemId, command, false, locked.version());
            return new SelectionSaveResult(workItemId, selectedLoanId, selectedOrder,
                    false, true, locked.version());
        }
        boolean changed = applySelection(draft, command.loanId(), command.requestedRepaymentOrder());
        if (!changed) {
            recordSelection(customerId, workItemId, command, false, locked.version());
            return new SelectionSaveResult(workItemId, selectedLoanId, selectedOrder,
                    false, false, locked.version());
        }
        draft.put("selectionRequestId", command.requestId());
        save(workItemId, draft);
        recordSelection(customerId, workItemId, command, true, locked.version() + 1);
        return new SelectionSaveResult(workItemId, command.loanId(), command.requestedRepaymentOrder(),
                true, false, locked.version() + 1);
    }

    private SelectionReceipt selectionReceipt(String requestId) {
        List<SelectionReceipt> rows = jdbc.query("""
                select entity_type,entity_id,event_type,actor_type,actor_id,payload
                  from event_log where request_id=?
                """, (rs, row) -> new SelectionReceipt(
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("event_type"),
                rs.getString("actor_type"), rs.getObject("actor_id", Long.class), rs.getString("payload")),
                selectionRequestId(requestId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireMatchingReceipt(SelectionReceipt receipt, long customerId, long workItemId,
            SelectionCommand command) {
        try {
            JsonNode payload = json.readTree(receipt.payload());
            boolean same = "WORK_ITEM".equals(receipt.entityType())
                    && Long.toString(workItemId).equals(receipt.entityId())
                    && SELECTION_EVENT.equals(receipt.eventType())
                    && "CUSTOMER".equals(receipt.actorType())
                    && Objects.equals(receipt.actorId(), customerId)
                    && payload.path("selectedLoanId").asLong() == command.loanId()
                    && command.requestedRepaymentOrder().equals(payload.path("requestedRepaymentOrder").asText());
            if (!same) throw duplicateSelectionRequest();
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DomainException("B01_STATE_PERSISTENCE_FAILED",
                    "Stored B-01 selection receipt could not be read.", true);
        }
    }

    private void recordSelection(long customerId, long workItemId, SelectionCommand command,
            boolean changed, int workItemVersion) {
        ObjectNode payload = json.createObjectNode()
                .put("selectedLoanId", command.loanId())
                .put("requestedRepaymentOrder", command.requestedRepaymentOrder())
                .put("changed", changed)
                .put("workItemVersion", workItemVersion);
        try {
            jdbc.update("""
                    insert into event_log(entity_type,entity_id,event_type,actor_type,actor_id,request_id,payload)
                    values('WORK_ITEM',?,?,'CUSTOMER',?,?,cast(? as json))
                    """, Long.toString(workItemId), SELECTION_EVENT, customerId,
                    selectionRequestId(command.requestId()), json.writeValueAsString(payload));
        } catch (Exception exception) {
            throw new DomainException("B01_STATE_PERSISTENCE_FAILED",
                    "B-01 selection receipt could not be stored.", true);
        }
    }

    private static String selectionRequestId(String requestId) {
        return SELECTION_REQUEST_PREFIX + requestId;
    }

    private static DomainException duplicateSelectionRequest() {
        return new DomainException("DUPLICATE_REQUEST_CONFLICT",
                "The requestId was already used for a different B-01 selection.");
    }

    @Transactional
    public B01EligibilityService.ValidationResult validateAndSave(long customerId, long workItemId,
            long loanId, String requestedRepaymentOrder) {
        LockedWorkItem locked = lock(customerId, workItemId, false, true);
        ObjectNode draft = draft(locked.draft());
        Long selectedLoanId = positiveLong(draft, "selectedLoanId");
        String selectedOrder = text(draft, "requestedRepaymentOrder");
        Long documentFileId = positiveLong(draft, "documentFileId");
        // Selection is a separate, persisted step. Do not let a stale client
        // validation request silently switch the loan after OCR was uploaded.
        if (!Objects.equals(selectedLoanId, loanId) || !Objects.equals(selectedOrder, requestedRepaymentOrder)) {
            throw new DomainException("B01_SELECTION_NOT_CURRENT",
                    "Validate the loan and repayment order currently selected for this application.");
        }
        if (documentFileId == null) {
            throw new DomainException("B01_APPLICATION_NOT_CURRENT",
                    "Upload an application for the current loan selection before validation.");
        }
        B01EligibilityService.ValidationResult result = eligibility.validate(
                customerId, workItemId, loanId, requestedRepaymentOrder, documentFileId);
        if (!Objects.equals(documentFileId, result.ocr().documentFileId())) {
            throw new DomainException("B01_APPLICATION_NOT_CURRENT",
                    "Upload an application for the current loan selection before validation.");
        }
        draft.set("lastEligibility", json.valueToTree(result));
        draft.put("lastEligibilityAt", Instant.now().toString());
        draft.put("currentStep", result.ocr().matched() ? "ELIGIBILITY_CHECKED" : "APPLICATION_REVIEW_REQUIRED");
        save(workItemId, draft);
        return result;
    }

    /** Called inside the document upload transaction after both file and OCR rows exist. */
    @Transactional
    void documentUploaded(long customerId, long workItemId, long documentFileId, int documentVersion) {
        documentUploaded(customerId, workItemId, documentFileId, documentVersion, null);
    }

    @Transactional
    void documentUploaded(long customerId, long workItemId, long documentFileId, int documentVersion,
                          String requestId) {
        LockedWorkItem locked = lock(customerId, workItemId, false, true);
        ObjectNode draft = draft(locked.draft());
        invalidateApplication(draft);
        draft.put("documentFileId", documentFileId).put("documentVersion", documentVersion)
                .put("currentStep", "APPLICATION_UPLOADED");
        if (requestId != null) draft.put("documentUploadRequestId", requestId);
        save(workItemId, draft);
    }

    @Transactional(readOnly = true)
    public ProgressContext context(long customerId, long workItemId) {
        LockedWorkItem item = lock(customerId, workItemId, true, false);
        ObjectNode draft = draft(item.draft());
        DocumentContext latestDocument = latestDocument(workItemId);
        DocumentContext document = documentReferencedByDraft(draft, latestDocument) ? latestDocument : null;
        B01EligibilityService.ValidationResult lastEligibility = lastEligibility(draft);

        Long selectedLoanId = positiveLong(draft, "selectedLoanId");
        String requestedOrder = text(draft, "requestedRepaymentOrder");
        boolean applicationCurrent = applicationCurrent(draft, document, selectedLoanId, requestedOrder);
        boolean consented = applicationCurrent && draft.path("consentAgreed").asBoolean(false)
                && consentExists(workItemId, positiveLong(draft, "consentRecordId"));
        boolean signed = consented && draft.path("mockSignatureCompleted").asBoolean(false);
        boolean smsVerified = signed && draft.path("smsVerified").asBoolean(false);
        boolean eligibilityCurrent = eligibilityCurrent(lastEligibility, selectedLoanId, requestedOrder, document);
        boolean eligibilityPassed = eligibilityCurrent && lastEligibility.loanRuleEligible()
                && lastEligibility.ocr().matched() && lastEligibility.image() != null
                && lastEligibility.image().matched();
        String currentStep = step(selectedLoanId, document, eligibilityCurrent, eligibilityPassed,
                consented, signed, smsVerified);

        return new ProgressContext(workItemId, item.version(), item.status(), item.resumeStep(), selectedLoanId, requestedOrder,
                document, eligibilityCurrent ? lastEligibility : null, consented, signed, smsVerified,
                currentStep, smsVerified);
    }

    private LockedWorkItem lock(long customerId, long workItemId, boolean allowOnHold, boolean forUpdate) {
        return lock(customerId, workItemId, allowOnHold, forUpdate, false);
    }

    private LockedWorkItem lock(long customerId, long workItemId, boolean allowOnHold, boolean forUpdate,
            boolean distinguishOnHold) {
        requireJdbc();
        String states = allowOnHold ? "('ACTIVE','ON_HOLD')" : "('ACTIVE')";
        List<LockedWorkItem> rows = jdbc.query("""
                select wi.version,wi.status,wi.resume_step,wi.draft_data
                  from work_item wi join workflow wf on wf.id=wi.workflow_id
                  join `user` u on u.id=wf.customer_id
                 where wi.id=? and wf.customer_id=? and wi.task_code='%s'
                   and wi.status in %s and u.is_active=true %s
                """.formatted(TASK_CODE, states, forUpdate ? "for update" : ""), (rs, row) -> new LockedWorkItem(
                rs.getInt("version"), rs.getString("status"), rs.getString("resume_step"),
                rs.getString("draft_data")),
                workItemId, customerId);
        if (rows.isEmpty()) {
            if (distinguishOnHold && ownedOnHold(customerId, workItemId)) {
                throw new DomainException("WORK_ITEM_RESUME_REQUIRED",
                        "The B-01 work item must be resumed before continuing.");
            }
            throw unavailable();
        }
        return rows.get(0);
    }

    private boolean ownedOnHold(long customerId, long workItemId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from work_item wi join workflow wf on wf.id=wi.workflow_id
                 where wi.id=? and wf.customer_id=? and wi.task_code=? and wi.status='ON_HOLD'
                """, Integer.class, workItemId, customerId, TASK_CODE);
        return count != null && count == 1;
    }

    private void requireOwnedLoan(long customerId, long loanId) {
        Integer count = jdbc.queryForObject("select count(*) from loan where id=? and customer_id=?",
                Integer.class, loanId, customerId);
        if (count == null || count != 1)
            throw new DomainException("LOAN_NOT_AVAILABLE", "The selected loan is not available.");
    }

    private DocumentContext latestDocument(long workItemId) {
        List<DocumentContext> rows = jdbc.query("""
                select df.id,df.version,df.original_name,df.size_bytes,df.content_type,df.uploaded_at,df.checksum,
                       de.method,de.status,de.extracted
                  from required_document rd
                  join document_file df on df.required_document_id=rd.id
                  left join document_extraction de on de.document_file_id=df.id
                 where rd.work_item_id=? and rd.document_type_code=?
                   and df.is_latest=true and df.deleted_at is null
                 order by de.id desc limit 1
                """, (rs, row) -> {
            String extracted = rs.getString("extracted");
            OcrContext ocr = extracted == null ? null : safeOcr(rs.getString("method"),
                    rs.getString("status"), extracted);
            return new DocumentContext(rs.getLong("id"), rs.getInt("version"),
                    rs.getString("original_name"), rs.getLong("size_bytes"), rs.getString("content_type"),
                    rs.getTimestamp("uploaded_at").toInstant(), ocr, rs.getString("checksum"));
        }, workItemId, B01DocumentService.DOCUMENT_TYPE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private OcrContext safeOcr(String method, String status, String extracted) {
        try {
            B01OcrPort.OcrResult result = json.readValue(extracted, B01OcrPort.OcrResult.class);
            List<OcrLoan> loans = result.fields() == null || result.fields().loans() == null ? List.of()
                    : result.fields().loans().stream().map(loan -> new OcrLoan(mask(loan.accountNumber()),
                            loan.balance(), loan.accelerationDate())).toList();
            String order = result.fields() == null ? null : result.fields().requestedRepaymentOrder();
            return new OcrContext(method, status, loans, order, result.authenticityVerified(),
                    result.manualVerificationRequired(), result.visualChecks());
        } catch (Exception exception) {
            return new OcrContext(method, "UNREADABLE", List.of(), null, false, true, null);
        }
    }

    private boolean applicationCurrent(ObjectNode draft, DocumentContext document, Long loanId, String order) {
        if (loanId == null || order == null || document == null
                || !Objects.equals(positiveLong(draft, "documentFileId"), document.documentFileId())
                || draft.path("documentVersion").asInt(-1) != document.version()) return false;
        String expected = sha256(loanId + "|" + order + "|" + document.documentFileId() + "|"
                + document.version() + "|" + document.checksum());
        return Objects.equals(text(draft, "applicationFingerprint"), expected)
                && Objects.equals(positiveLong(draft, "documentFileId"), document.documentFileId())
                && Objects.equals(text(draft, "documentChecksum"), document.checksum());
    }

    private static boolean eligibilityCurrent(B01EligibilityService.ValidationResult result, Long loanId,
            String order, DocumentContext document) {
        return result != null && loanId != null && document != null
                && result.loanId() == loanId && Objects.equals(result.requestedRepaymentOrder(), order)
                && result.ocr() != null && Objects.equals(result.ocr().documentFileId(), document.documentFileId());
    }

    private static boolean documentReferencedByDraft(ObjectNode draft, DocumentContext document) {
        return document != null
                && Objects.equals(positiveLong(draft, "documentFileId"), document.documentFileId())
                && draft.path("documentVersion").asInt(-1) == document.version();
    }

    private B01EligibilityService.ValidationResult lastEligibility(ObjectNode draft) {
        JsonNode value = draft.get("lastEligibility");
        if (value == null || value.isNull()) return null;
        try { return json.treeToValue(value, B01EligibilityService.ValidationResult.class); }
        catch (Exception exception) {
            throw new DomainException("WORK_ITEM_DRAFT_INVALID", "Stored B-01 state is invalid.", true);
        }
    }

    private boolean consentExists(long workItemId, Long consentId) {
        if (consentId == null) return false;
        Integer count = jdbc.queryForObject("""
                select count(*) from consent_record
                 where id=? and work_item_id=? and consent_type=? and agreed=true
                """, Integer.class, consentId, workItemId, B01VerificationService.CONSENT_TYPE);
        return count != null && count == 1;
    }

    private boolean applySelection(ObjectNode draft, long loanId, String order) {
        Long beforeLoan = positiveLong(draft, "selectedLoanId");
        String beforeOrder = text(draft, "requestedRepaymentOrder");
        if (!Objects.equals(beforeLoan, loanId) || !Objects.equals(beforeOrder, order)) {
            invalidateApplication(draft);
            draft.put("selectedLoanId", loanId).put("requestedRepaymentOrder", order)
                    .put("currentStep", "LOAN_SELECTED");
            return true;
        }
        return false;
    }

    private static void invalidateApplication(ObjectNode draft) {
        List.of("documentFileId","documentVersion","documentUploadRequestId","lastEligibility","lastEligibilityAt",
                "applicationFingerprint","documentChecksum",
                "consentRecordId","consentAgreed","consentRequestId","mockSignatureCompleted",
                "signatureRequestId","signatureStorageKey","signatureSha256","smsChallengeId",
                "smsOtpHmac","smsExpiresAt","smsAttempts","smsRequestId","smsRequestedAt",
                "smsDeliveryStatus","smsVerified","smsVerifiedAt","smsRequestCount").forEach(draft::remove);
    }

    private void save(long workItemId, ObjectNode draft) {
        try {
            jdbc.update("update work_item set draft_data=cast(? as json),last_activity_at=current_timestamp(6),"
                    + "version=version+1 where id=?", json.writeValueAsString(draft), workItemId);
        } catch (Exception exception) {
            throw new DomainException("B01_STATE_PERSISTENCE_FAILED", "B-01 state could not be stored.", true);
        }
    }

    private ObjectNode draft(String value) {
        try { return value == null ? json.createObjectNode() : (ObjectNode) json.readTree(value); }
        catch (Exception exception) {
            throw new DomainException("WORK_ITEM_DRAFT_INVALID", "Stored B-01 state is invalid.", true);
        }
    }

    private static String step(Long loanId, DocumentContext document, boolean eligibilityCurrent,
            boolean eligibilityPassed, boolean consented, boolean signed, boolean smsVerified) {
        if (loanId == null) return "LOAN_SELECTION";
        if (document == null) return "APPLICATION_UPLOAD";
        if (!eligibilityCurrent) return "ELIGIBILITY_VALIDATION";
        if (!eligibilityPassed) return "ELIGIBILITY_REVIEW";
        if (!consented) return "CONSENT";
        if (!signed) return "MOCK_SIGNATURE";
        if (!smsVerified) return "SMS_VERIFICATION";
        return "READY_TO_EXECUTE";
    }

    private static String mask(String account) {
        if (account == null || account.length() < 4) return "****";
        return "****" + account.substring(account.length() - 4);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static Long positiveLong(ObjectNode draft, String name) {
        JsonNode value = draft.get(name);
        return value == null || !value.canConvertToLong() || value.asLong() <= 0 ? null : value.asLong();
    }
    private static String text(ObjectNode draft, String name) {
        JsonNode value = draft.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64)
            throw new DomainException("INVALID_REQUEST_ID", "A requestId of at most 64 characters is required.");
    }
    private static void requestedOrder(String order) {
        if (!"INTEREST_FIRST".equals(order) && !"PRINCIPAL_FIRST".equals(order))
            throw new DomainException("INVALID_REPAYMENT_ORDER",
                    "Requested repayment order must be INTEREST_FIRST or PRINCIPAL_FIRST.");
    }
    private void requireJdbc() {
        if (jdbc == null) throw new DomainException("B01_PERSISTENCE_UNAVAILABLE",
                "B-01 persistence is not configured.", true);
    }
    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    public record SelectionCommand(String requestId, long loanId, String requestedRepaymentOrder) { }
    public record SelectionSaveResult(long workItemId, Long selectedLoanId, String requestedRepaymentOrder,
                                      boolean changed, boolean replayed, int workItemVersion) { }
    public record ProgressContext(long workItemId, int workItemVersion, String workItemStatus, String resumeStep,
                                  Long selectedLoanId, String requestedRepaymentOrder,
                                  DocumentContext applicationDocument,
                                  B01EligibilityService.ValidationResult lastEligibility,
                                  boolean consentAgreed, boolean mockSignatureCompleted,
                                  boolean smsVerified, String currentStep, boolean readyToExecute) { }
    public record DocumentContext(long documentFileId, int version, String displayName, long sizeBytes,
                                  String contentType, Instant uploadedAt, OcrContext ocr,
                                  @JsonIgnore String checksum) { }
    public record OcrContext(String method, String status, List<OcrLoan> loans,
                             String requestedRepaymentOrder, boolean authenticityVerified,
                             boolean manualVerificationRequired, B01OcrPort.VisualChecks visualChecks) { }
    public record OcrLoan(String maskedAccountNumber, BigDecimal balance, LocalDate accelerationDate) { }
    private record LockedWorkItem(int version, String status, String resumeStep, String draft) { }
    private record SelectionReceipt(String entityType, String entityId, String eventType,
                                    String actorType, Long actorId, String payload) { }
}
