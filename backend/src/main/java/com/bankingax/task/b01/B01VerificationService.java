package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class B01VerificationService {
    static final String NOTICE_VERSION = "B01_NOTICE_V1";
    static final String CONSENT_TYPE = "B01_REPAYMENT_ORDER_NOTICE";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final B01EligibilityService eligibility;
    private final B01SignatureStorage signatures;
    private final B01SmsSender sms;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] hmacSecret;
    private final long otpTtl;
    private final int maxAttempts;
    private final long resendSeconds;
    private final int maxRequests;
    private final SecureRandom random = new SecureRandom();

    B01VerificationService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> transactions,
            B01EligibilityService eligibility, B01SignatureStorage signatures, B01SmsSender sms,
            ObjectMapper json, ObjectProvider<Clock> clock,
            @Value("${app.sms.otp-hmac-secret:}") String hmacSecret,
            @Value("${app.sms.otp-ttl-seconds:180}") long otpTtl,
            @Value("${app.sms.max-attempts:5}") int maxAttempts,
            @Value("${app.sms.resend-seconds:60}") long resendSeconds,
            @Value("${app.sms.max-requests:5}") int maxRequests) {
        this.jdbc = jdbc.getIfAvailable(); this.transactions = transactions.getIfAvailable();
        this.eligibility = eligibility; this.signatures = signatures; this.sms = sms; this.json = json;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.hmacSecret = hmacSecret == null ? new byte[0] : hmacSecret.getBytes(StandardCharsets.UTF_8);
        this.otpTtl = otpTtl; this.maxAttempts = maxAttempts; this.resendSeconds = resendSeconds;
        this.maxRequests = maxRequests;
    }

    public ConsentResult consent(long customerId, long workItemId, ConsentCommand command) {
        validateRequest(command.requestId());
        if (!command.agreed()) throw new DomainException("CONSENT_REQUIRED", "Explicit consent is required.");
        if (!NOTICE_VERSION.equals(command.noticeVersion()))
            throw new DomainException("NOTICE_VERSION_INVALID", "The current B-01 notice version is required.");
        var validated = validateApplication(customerId, workItemId, command.loanId(),
                command.requestedRepaymentOrder(), command.documentFileId());
        return requiredTransaction().execute(status -> {
            Snapshot snapshot = lockSnapshot(customerId, workItemId, command.loanId(),
                    command.requestedRepaymentOrder(), command.documentFileId());
            ObjectNode draft = draft(snapshot.draft());
            if (snapshot.fingerprint().equals(text(draft, "applicationFingerprint"))
                    && draft.path("consentAgreed").asBoolean(false))
                return new ConsentResult(draft.path("consentRecordId").asLong(), snapshot.fingerprint(), "CONSENT_COMPLETED");
            long consentId = insertConsent(workItemId);
            application(draft, snapshot);
            draft.set("lastEligibility", json.valueToTree(validated));
            draft.put("lastEligibilityAt", clock.instant().toString());
            draft.put("consentRecordId", consentId).put("consentAgreed", true)
                    .put("consentRequestId", command.requestId()).put("currentStep", "CONSENT_COMPLETED");
            clearAfterConsent(draft);
            saveDraft(workItemId, draft);
            event(workItemId, "B01_CONSENT_COMPLETED", customerId, command.requestId() + ":consent",
                    object().put("consentRecordId", consentId).put("applicationFingerprint", snapshot.fingerprint()));
            return new ConsentResult(consentId, snapshot.fingerprint(), "CONSENT_COMPLETED");
        });
    }

    public SignatureResult signature(long customerId, long workItemId, String requestId, MultipartFile file) {
        validateRequest(requestId);
        SignaturePreparation preparation = requiredTransaction().execute(status -> prepareSignature(customerId, workItemId, requestId));
        if (preparation.existing()) return new SignatureResult(true, "SMS_VERIFICATION");
        B01SignatureStorage.StoredSignature stored = signatures.store(file);
        try {
            SignatureCompletion result = requiredTransaction().execute(status -> completeSignature(
                    customerId, workItemId, requestId, preparation.fingerprint(), stored));
            if (!stored.storageKey().equals(result.storageKey())) signatures.deleteQuietly(stored.storageKey());
            return result.result();
        } catch (RuntimeException exception) {
            signatures.deleteQuietly(stored.storageKey()); throw exception;
        }
    }

    public SmsIssue requestSms(long customerId, long workItemId, String requestId) {
        validateRequest(requestId); requireHmacSecret();
        String otp = "%06d".formatted(random.nextInt(1_000_000));
        SmsPrepared prepared = requiredTransaction().execute(status -> prepareSms(customerId, workItemId, requestId, otp));
        if (prepared.existing() != null) return prepared.existing();
        try {
            B01SmsSender.SendReceipt receipt = sms.send(prepared.phone(),
                    "[Banking-AX] 인증번호는 " + otp + "입니다. 3분 내 입력해 주세요.");
            if (!"ACCEPTED".equals(receipt.acceptanceStatus()))
                throw new DomainException("SMS_DELIVERY_UNAVAILABLE", "The verification message could not be sent.", true);
        } catch (RuntimeException exception) {
            requiredTransaction().executeWithoutResult(status -> markDeliveryFailed(workItemId, prepared.challengeId()));
            throw exception;
        }
        return requiredTransaction().execute(status -> markAccepted(customerId, workItemId, prepared));
    }

    public SmsVerification verifySms(long customerId, long workItemId, String requestId,
                                     String challengeId, String otp) {
        validateRequest(requestId); requireHmacSecret();
        if (challengeId == null || challengeId.isBlank() || otp == null || !otp.matches("\\d{6}"))
            throw new DomainException("OTP_INVALID", "The verification code is invalid.");
        VerificationAttempt attempt = requiredTransaction().execute(status -> {
            Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
            requireCurrentApplication(customerId, workItemId, draft);
            if (draft.path("smsVerified").asBoolean(false))
                return VerificationAttempt.error("OTP_ALREADY_USED", "The verification code has already been used.");
            if (!challengeId.equals(text(draft, "smsChallengeId")) || !isAcceptedStatus(text(draft, "smsDeliveryStatus")))
                return VerificationAttempt.error("OTP_INVALID", "The verification code is invalid.");
            Instant now = clock.instant(), expires = instant(draft, "smsExpiresAt");
            if (expires == null || !now.isBefore(expires)) {
                draft.put("smsDeliveryStatus", "EXPIRED"); saveDraft(workItemId, draft);
                return VerificationAttempt.error("OTP_EXPIRED", "The verification code has expired.");
            }
            int attempts = draft.path("smsAttempts").asInt(0);
            if (attempts >= maxAttempts)
                return VerificationAttempt.error("OTP_ATTEMPTS_EXCEEDED", "OTP attempts exceeded.");
            if (!MessageDigest.isEqual(text(draft, "smsOtpHmac").getBytes(StandardCharsets.UTF_8),
                    otpHmac(challengeId, text(draft, "applicationFingerprint"), otp).getBytes(StandardCharsets.UTF_8))) {
                attempts++; draft.put("smsAttempts", attempts);
                if (attempts >= maxAttempts) draft.put("smsDeliveryStatus", "LOCKED");
                saveDraft(workItemId, draft);
                return VerificationAttempt.error(attempts >= maxAttempts ? "OTP_ATTEMPTS_EXCEEDED" : "OTP_INVALID",
                        attempts >= maxAttempts ? "OTP attempts exceeded." : "The verification code is invalid.");
            }
            draft.remove("smsOtpHmac"); draft.put("smsVerified", true).put("smsVerifiedAt", now.toString())
                    .put("smsDeliveryStatus", "VERIFIED").put("currentStep", "READY_FOR_FINAL_VALIDATION");
            saveDraft(workItemId, draft);
            event(workItemId, "B01_SMS_VERIFIED", customerId, requestId + ":sms-verified",
                    object().put("challengeId", challengeId)
                            .put("applicationFingerprint", text(draft, "applicationFingerprint")));
            return VerificationAttempt.success(new SmsVerification(true, "READY_FOR_FINAL_VALIDATION"));
        });
        if (attempt.errorCode() != null) throw new DomainException(attempt.errorCode(), attempt.errorMessage());
        return attempt.result();
    }

    public VerificationStatus status(long customerId, long workItemId) {
        return requiredTransaction().execute(status -> {
            Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
            boolean current = isCurrentApplication(customerId, workItemId, draft);
            boolean consented = current && draft.path("consentAgreed").asBoolean(false);
            boolean signed = consented && draft.path("mockSignatureCompleted").asBoolean(false);
            boolean verified = signed && draft.path("smsVerified").asBoolean(false);
            String step = !current && draft.hasNonNull("applicationFingerprint") ? "APPLICATION_CHANGED"
                    : verified ? "READY_FOR_FINAL_VALIDATION" : signed ? "SMS_VERIFICATION"
                    : consented ? "MOCK_SIGNATURE" : "CONSENT";
            return new VerificationStatus(workItemId, consented, consented && !signed, signed, verified, step);
        });
    }

    private B01EligibilityService.ValidationResult validateApplication(long customerId, long workItemId,
            long loanId, String requestedOrder, long documentFileId) {
        var result = eligibility.validate(customerId, workItemId, loanId, requestedOrder);
        if (!result.loanRuleEligible() || !result.ocr().matched()
                || !Objects.equals(result.ocr().documentFileId(), documentFileId))
            throw new DomainException("B01_APPLICATION_NOT_VALIDATABLE", "The selected loan and application do not match.");
        return result;
    }

    private Snapshot lockSnapshot(long customerId, long workItemId, long loanId, String order, long documentFileId) {
        Locked locked = lock(customerId, workItemId);
        Snapshot snapshot = jdbc.query("""
                select df.id,df.version,df.checksum
                  from required_document rd join document_file df on df.required_document_id=rd.id
                 where rd.work_item_id=? and rd.document_type_code='B01_REPAYMENT_ORDER_APPLICATION'
                   and df.id=? and df.is_latest=true and df.deleted_at is null for update
                """, rs -> rs.next() ? new Snapshot(loanId, order, rs.getLong(1), rs.getInt(2),
                rs.getString(3), null, locked.draft()) : null, workItemId, documentFileId);
        if (snapshot == null) throw new DomainException("DOCUMENT_NOT_AVAILABLE", "The current application document is not available.");
        return snapshot.withFingerprint(fingerprint(snapshot));
    }

    private SignaturePreparation prepareSignature(long customerId, long workItemId, String requestId) {
        Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
        requireCurrentApplication(customerId, workItemId, draft);
        if (!draft.path("consentAgreed").asBoolean(false)) throw new DomainException("CONSENT_REQUIRED", "Consent is required before mock signature.");
        if (requestId.equals(text(draft, "signatureRequestId")) && draft.path("mockSignatureCompleted").asBoolean(false))
            return new SignaturePreparation(text(draft, "applicationFingerprint"), true);
        if (draft.path("mockSignatureCompleted").asBoolean(false))
            throw new DomainException("MOCK_SIGNATURE_ALREADY_COMPLETED", "A mock signature is already recorded for this application.");
        return new SignaturePreparation(text(draft, "applicationFingerprint"), false);
    }

    private SignatureCompletion completeSignature(long customerId, long workItemId, String requestId,
            String fingerprint, B01SignatureStorage.StoredSignature stored) {
        Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
        requireCurrentApplication(customerId, workItemId, draft);
        if (!fingerprint.equals(text(draft, "applicationFingerprint"))) throw changed();
        if (requestId.equals(text(draft, "signatureRequestId")) && draft.path("mockSignatureCompleted").asBoolean(false))
            return signatureCompletion(draft);
        draft.put("mockSignatureCompleted", true).put("signatureRequestId", requestId)
                .put("signatureStorageKey", stored.storageKey()).put("signatureSha256", stored.sha256())
                .put("currentStep", "SMS_VERIFICATION");
        saveDraft(workItemId, draft);
        event(workItemId, "B01_MOCK_SIGNATURE_COMPLETED", customerId, requestId + ":signature",
                object().put("consentRecordId", draft.path("consentRecordId").asLong())
                        .put("signatureStorageKey", stored.storageKey()).put("signatureSha256", stored.sha256())
                        .put("applicationFingerprint", fingerprint));
        return signatureCompletion(draft);
    }

    private SmsPrepared prepareSms(long customerId, long workItemId, String requestId, String otp) {
        Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
        if (locked.phone() == null || locked.phone().isBlank())
            throw new DomainException("REGISTERED_PHONE_NOT_AVAILABLE", "A registered phone number is required.");
        requireCurrentApplication(customerId, workItemId, draft);
        if (!draft.path("mockSignatureCompleted").asBoolean(false))
            throw new DomainException("MOCK_SIGNATURE_REQUIRED", "Mock signature is required before SMS verification.");
        if (draft.path("smsVerified").asBoolean(false)) throw new DomainException("SMS_ALREADY_VERIFIED", "SMS verification is already complete.");
        Instant now = clock.instant();
        boolean sameRequest = requestId.equals(text(draft, "smsRequestId"));
        if (sameRequest && isAcceptedStatus(text(draft, "smsDeliveryStatus")))
            return new SmsPrepared(null, null, null, new SmsIssue(text(draft, "smsChallengeId"),
                    mask(locked.phone()), instant(draft, "smsExpiresAt"), "ACCEPTED"));
        if (sameRequest && "PENDING".equals(text(draft, "smsDeliveryStatus")))
            throw new DomainException("SMS_REQUEST_IN_PROGRESS", "The verification message request is already in progress.", true);
        Instant last = instant(draft, "smsRequestedAt");
        if (last != null && now.isBefore(last.plusSeconds(resendSeconds)))
            throw new DomainException("SMS_RESEND_TOO_SOON", "Please wait before requesting another code.");
        int requests = draft.path("smsRequestCount").asInt(0) + 1;
        if (requests > maxRequests) throw new DomainException("SMS_REQUEST_LIMIT_EXCEEDED", "SMS request limit exceeded.");
        String challenge = UUID.randomUUID().toString();
        draft.put("smsChallengeId", challenge).put("smsOtpHmac", otpHmac(challenge,
                        text(draft, "applicationFingerprint"), otp)).put("smsExpiresAt", now.plusSeconds(otpTtl).toString())
                .put("smsAttempts", 0).put("smsRequestCount", requests).put("smsRequestId", requestId)
                .put("smsRequestedAt", now.toString()).put("smsDeliveryStatus", "PENDING").put("smsVerified", false);
        saveDraft(workItemId, draft);
        return new SmsPrepared(challenge, locked.phone(), text(draft, "applicationFingerprint"), null);
    }

    private SmsIssue markAccepted(long customerId, long workItemId, SmsPrepared prepared) {
        Locked locked = lock(customerId, workItemId); ObjectNode draft = draft(locked.draft());
        if (!prepared.challengeId().equals(text(draft, "smsChallengeId"))) throw changed();
        draft.put("smsDeliveryStatus", "ACCEPTED"); saveDraft(workItemId, draft);
        event(workItemId, "B01_SMS_ACCEPTED", customerId, text(draft, "smsRequestId") + ":sms-accepted",
                object().put("challengeId", prepared.challengeId()).put("applicationFingerprint", prepared.fingerprint()));
        return new SmsIssue(prepared.challengeId(), mask(locked.phone()), instant(draft, "smsExpiresAt"), "ACCEPTED");
    }

    private void markDeliveryFailed(long workItemId, String challenge) {
        ObjectNode draft = draft(jdbc.queryForObject("select draft_data from work_item where id=? for update", String.class, workItemId));
        if (challenge.equals(text(draft, "smsChallengeId"))) {
            draft.remove("smsOtpHmac"); draft.put("smsDeliveryStatus", "FAILED"); saveDraft(workItemId, draft);
        }
    }

    private Locked lock(long customerId, long workItemId) {
        requirePersistence();
        List<Locked> rows = jdbc.query("""
                select wi.draft_data,u.phone from work_item wi join workflow wf on wf.id=wi.workflow_id
                  join `user` u on u.id=wf.customer_id
                 where wi.id=? and wf.customer_id=? and wi.task_code='REPAYMENT_ORDER_CHANGE'
                   and wi.status='ACTIVE' and u.is_active=true for update
                """, (rs, row) -> new Locked(rs.getString(1), rs.getString(2)), workItemId, customerId);
        if (rows.isEmpty()) throw new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
        return rows.get(0);
    }

    private void requireCurrentApplication(long customerId, long workItemId, ObjectNode draft) {
        if (!isCurrentApplication(customerId, workItemId, draft)) throw changed();
    }
    private boolean isCurrentApplication(long customerId, long workItemId, ObjectNode draft) {
        if (!draft.hasNonNull("applicationFingerprint")) return false;
        try {
            Snapshot current = lockSnapshot(customerId, workItemId, draft.path("selectedLoanId").asLong(),
                    text(draft, "requestedRepaymentOrder"), draft.path("documentFileId").asLong());
            validateApplication(customerId, workItemId, current.loanId(), current.order(), current.documentFileId());
            return current.fingerprint().equals(text(draft, "applicationFingerprint"));
        } catch (DomainException exception) { return false; }
    }

    private long insertConsent(long workItemId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(
                "insert into consent_record(work_item_id,consent_type,notice_version,agreed) values(?,?,?,true)",
                Statement.RETURN_GENERATED_KEYS); statement.setLong(1, workItemId); statement.setString(2, CONSENT_TYPE);
            statement.setString(3, NOTICE_VERSION); return statement; }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private void event(long workItemId, String type, long customerId, String requestId, JsonNode payload) {
        jdbc.update("insert into event_log(entity_type,entity_id,event_type,actor_type,actor_id,request_id,payload) "
                + "values('WORK_ITEM',? ,?,'CUSTOMER',?,?,cast(? as json))", Long.toString(workItemId), type,
                customerId, requestId, write(payload));
    }
    private void saveDraft(long workItemId, ObjectNode draft) {
        jdbc.update("update work_item set draft_data=cast(? as json),last_activity_at=current_timestamp(6),version=version+1 where id=?",
                write(draft), workItemId);
    }
    private ObjectNode draft(String value) {
        try { return value == null ? object() : (ObjectNode) json.readTree(value); }
        catch (Exception exception) { throw new DomainException("WORK_ITEM_DRAFT_INVALID", "Stored B-01 state is invalid.", true); }
    }
    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new DomainException("B01_STATE_PERSISTENCE_FAILED", "B-01 state could not be stored.", true); }
    }
    private ObjectNode object() { return json.createObjectNode(); }
    private void application(ObjectNode draft, Snapshot snapshot) {
        draft.put("selectedLoanId", snapshot.loanId()).put("requestedRepaymentOrder", snapshot.order())
                .put("documentFileId", snapshot.documentFileId()).put("documentVersion", snapshot.documentVersion())
                .put("documentChecksum", snapshot.checksum()).put("applicationFingerprint", snapshot.fingerprint());
    }
    private static void clearAfterConsent(ObjectNode draft) {
        List.of("mockSignatureCompleted","signatureRequestId","signatureStorageKey","signatureSha256",
                "smsChallengeId","smsOtpHmac","smsExpiresAt","smsAttempts","smsRequestId","smsRequestedAt",
                "smsDeliveryStatus","smsVerified","smsVerifiedAt","smsRequestCount").forEach(draft::remove);
    }
    private String fingerprint(Snapshot s) {
        return sha256(s.loanId() + "|" + s.order() + "|" + s.documentFileId() + "|" + s.documentVersion() + "|" + s.checksum());
    }
    private String otpHmac(String challenge, String fingerprint, String otp) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((challenge + "|" + fingerprint + "|" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String text(ObjectNode node, String name) { JsonNode value=node.get(name); return value==null||value.isNull()?null:value.asText(); }
    private static Instant instant(ObjectNode node, String name) { String value=text(node,name); return value==null?null:Instant.parse(value); }
    private static String mask(String phone) { String value=phone.replaceAll("[^0-9]",""); return value.length()<7?"***":value.substring(0,3)+"-****-"+value.substring(value.length()-4); }
    // DELIVERED was the legacy label for provider acceptance; retain it only for in-flight challenges.
    private static boolean isAcceptedStatus(String status) { return "ACCEPTED".equals(status) || "DELIVERED".equals(status); }
    private static void validateRequest(String id) { if(id==null||id.isBlank()||id.length()>64) throw new DomainException("INVALID_REQUEST_ID","A requestId of at most 64 characters is required."); }
    private void requireHmacSecret() { if(hmacSecret.length<32) throw new DomainException("SMS_VERIFICATION_NOT_CONFIGURED","SMS verification is not safely configured.",true); }
    private void requirePersistence() { if(jdbc==null||transactions==null) throw new DomainException("B01_PERSISTENCE_UNAVAILABLE","B-01 persistence is not configured.",true); }
    private TransactionTemplate requiredTransaction() { requirePersistence(); return transactions; }
    private static DomainException changed() { return new DomainException("B01_APPLICATION_CHANGED","The loan or application changed. Consent and verification must be repeated."); }
    private static SignatureCompletion signatureCompletion(ObjectNode d) {
        return new SignatureCompletion(new SignatureResult(true,"SMS_VERIFICATION"), text(d,"signatureStorageKey"));
    }

    public record ConsentCommand(String requestId,long loanId,String requestedRepaymentOrder,long documentFileId,String noticeVersion,boolean agreed) { }
    public record ConsentResult(long consentRecordId,String applicationFingerprint,String currentStep) { }
    public record SignatureResult(boolean mockSignatureCompleted,String currentStep) { }
    public record SmsIssue(String challengeId,String maskedPhone,Instant expiresAt,String deliveryStatus) { }
    public record SmsVerification(boolean verified,String currentStep) { }
    public record VerificationStatus(long workItemId,boolean consentAgreed,boolean canSign,boolean mockSignatureCompleted,boolean smsVerified,String currentStep) { }
    private record Locked(String draft,String phone) { }
    private record Snapshot(long loanId,String order,long documentFileId,int documentVersion,String checksum,String fingerprint,String draft) {
        Snapshot withFingerprint(String value){return new Snapshot(loanId,order,documentFileId,documentVersion,checksum,value,draft);}
    }
    private record SignaturePreparation(String fingerprint,boolean existing) { }
    private record SignatureCompletion(SignatureResult result,String storageKey) { }
    private record SmsPrepared(String challengeId,String phone,String fingerprint,SmsIssue existing) { }
    private record VerificationAttempt(SmsVerification result,String errorCode,String errorMessage) {
        static VerificationAttempt success(SmsVerification result){return new VerificationAttempt(result,null,null);}
        static VerificationAttempt error(String code,String message){return new VerificationAttempt(null,code,message);}
    }
}
