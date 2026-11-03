package com.bankingax.task.b01;

import com.bankingax.core.api.DomainException;
import com.bankingax.task.c02.C02FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class B01DocumentService {
    static final String DOCUMENT_TYPE = "B01_REPAYMENT_ORDER_APPLICATION";
    private final JdbcTemplate jdbc;
    private final B01FileStorage files;
    private final B01OcrPort ocr;
    private final ObjectMapper json;

    B01DocumentService(ObjectProvider<JdbcTemplate> jdbc, B01FileStorage files,
                       B01OcrPort ocr, ObjectMapper json) {
        this.jdbc = jdbc.getIfAvailable();
        this.files = files;
        this.ocr = ocr;
        this.json = json;
    }

    @Transactional
    public UploadResult upload(long customerId, long workItemId, MultipartFile upload) {
        requireJdbc();
        requireOwnedActiveWorkItem(customerId, workItemId);
        C02FileStorage.StoredFile stored = files.store(upload);
        try {
            long requiredDocumentId = requiredDocument(workItemId);
            int version = nextVersion(requiredDocumentId);
            jdbc.update("update document_file set is_latest=false where required_document_id=? and is_latest=true",
                    requiredDocumentId);
            long documentFileId = insertFile(requiredDocumentId, version, customerId, stored);
            jdbc.update("update required_document set status='UPLOADED' where id=?", requiredDocumentId);
            B01OcrPort.OcrResult extracted = extractOnceLocked(documentFileId, stored.checksum(),
                    stored.storageKey(), stored.originalFilename(), stored.contentType());
            return safeResult(documentFileId, version, extracted);
        } catch (RuntimeException exception) {
            files.deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    /** Internal-only replay hook. The row lock and existence check prevent duplicate extraction rows. */
    @Transactional
    UploadResult extractExisting(long customerId, long workItemId, long documentFileId) {
        requireJdbc();
        requireOwnedActiveWorkItem(customerId, workItemId);
        FileRow file = jdbc.query("""
                select df.id,df.version,df.checksum,df.storage_path,df.original_name,df.content_type
                  from document_file df
                  join required_document rd on rd.id=df.required_document_id
                 where df.id=? and rd.work_item_id=? and rd.document_type_code=?
                 for update
                """, rs -> rs.next() ? new FileRow(rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)) : null,
                documentFileId, workItemId, DOCUMENT_TYPE);
        if (file == null) throw unavailable();
        return safeResult(file.id(), file.version(), extractOnceLocked(file.id(), file.checksum(),
                file.storageKey(), file.originalName(), file.contentType()));
    }

    private B01OcrPort.OcrResult extractOnceLocked(long documentFileId, String checksum, String storageKey,
                                                   String originalName, String contentType) {
        jdbc.queryForObject("select id from document_file where id=? for update", Long.class, documentFileId);
        List<String> saved = jdbc.query("select extracted from document_extraction where document_file_id=? order by id",
                (rs, row) -> rs.getString(1), documentFileId);
        if (!saved.isEmpty()) return read(saved.get(0));

        B01OcrPort.OcrResult result;
        try {
            result = ocr.extract(new B01OcrPort.ExtractRequest(documentFileId, DOCUMENT_TYPE, checksum,
                    originalName, contentType, files.read(storageKey)));
        } catch (RuntimeException exception) {
            result = new B01OcrPort.OcrResult(DOCUMENT_TYPE, null, ocr.method(), "FAILED_NEEDS_STAFF",
                    new B01OcrPort.Fields(List.of(), null),
                    new B01OcrPort.SignatureChecks("NOT_VERIFIED", "NOT_VERIFIED"),
                    false, true, "OCR_UNAVAILABLE");
        }
        try {
            jdbc.update("""
                    insert into document_extraction
                      (document_file_id,case_id,revision,method,status,extracted,failure_reason)
                    values (?,null,null,?,?,cast(? as json),?)
                    """, documentFileId, result.method(), result.status(), json.writeValueAsString(result),
                    result.failureReason());
            return result;
        } catch (Exception exception) {
            throw new DomainException("OCR_RESULT_PERSISTENCE_FAILED", "OCR processing result could not be stored.", true);
        }
    }

    private void requireOwnedActiveWorkItem(long customerId, long workItemId) {
        List<String> states = jdbc.query("""
                select wi.status
                  from work_item wi join workflow wf on wf.id=wi.workflow_id
                 where wi.id=? and wf.customer_id=? and wi.task_code='REPAYMENT_ORDER_CHANGE'
                 for update
                """, (rs, row) -> rs.getString(1), workItemId, customerId);
        if (states.isEmpty()) throw unavailable();
        if (!"ACTIVE".equals(states.get(0)))
            throw new DomainException("WORK_ITEM_NOT_UPLOADABLE", "The B-01 work item is not active.");
    }

    private long requiredDocument(long workItemId) {
        List<Long> ids = jdbc.query("""
                select id from required_document
                 where work_item_id=? and document_type_code=? for update
                """, (rs, row) -> rs.getLong(1), workItemId, DOCUMENT_TYPE);
        if (!ids.isEmpty()) return ids.get(0);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into required_document
                      (work_item_id,document_type_code,requirement,rule_version,status)
                    values (?,?,'REQUIRED','1.7.1','NOT_SUBMITTED')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, workItemId);
            statement.setString(2, DOCUMENT_TYPE);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private int nextVersion(long requiredDocumentId) {
        Integer version = jdbc.queryForObject(
                "select coalesce(max(version),0)+1 from document_file where required_document_id=?",
                Integer.class, requiredDocumentId);
        return version == null ? 1 : version;
    }

    private long insertFile(long requiredDocumentId, int version, long customerId,
                            C02FileStorage.StoredFile stored) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into document_file
                      (required_document_id,version,is_latest,original_name,extension,size_bytes,
                       storage_path,content_type,checksum,case_revision,uploaded_by)
                    values (?,?,true,?,?,?,?,?,?,0,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, requiredDocumentId);
            statement.setInt(2, version);
            statement.setString(3, stored.originalFilename());
            statement.setString(4, extension(stored.originalFilename()));
            statement.setLong(5, stored.fileSize());
            statement.setString(6, stored.storageKey());
            statement.setString(7, stored.contentType());
            statement.setString(8, stored.checksum());
            statement.setLong(9, customerId);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private UploadResult safeResult(long documentFileId, int version, B01OcrPort.OcrResult result) {
        List<SafeLoan> loans = result.fields().loans().stream()
                .map(loan -> new SafeLoan(mask(loan.accountNumber()), loan.balance(), loan.accelerationDate()))
                .toList();
        return new UploadResult(documentFileId, version, result.documentTypeCode(), result.detectedType(),
                result.method(), result.status(), new SafeFields(loans, result.fields().requestedRepaymentOrder()),
                new SafeSignatureChecks(result.signatureChecks().debtorSignatureField(),
                        result.signatureChecks().thirdPartySignatureField()),
                result.authenticityVerified(), result.manualVerificationRequired());
    }

    private B01OcrPort.OcrResult read(String value) {
        try {
            return json.readValue(value, B01OcrPort.OcrResult.class);
        } catch (Exception exception) {
            throw new DomainException("OCR_RESULT_UNREADABLE", "Stored OCR result could not be read.", true);
        }
    }

    private void requireJdbc() {
        if (jdbc == null) throw new DomainException("DOCUMENT_PERSISTENCE_UNAVAILABLE",
                "B-01 document persistence is not configured.", true);
    }

    private static DomainException unavailable() {
        return new DomainException("WORK_ITEM_NOT_AVAILABLE", "The B-01 work item is not available.");
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 1 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String mask(String account) {
        if (account == null || account.length() < 4) return "****";
        return "****" + account.substring(account.length() - 4);
    }

    private record FileRow(long id, int version, String checksum, String storageKey,
                           String originalName, String contentType) { }

    public record UploadResult(long documentFileId, int version, String documentTypeCode,
                               String detectedType, String method, String status, SafeFields fields,
                               SafeSignatureChecks signatureChecks,
                               boolean authenticityVerified, boolean manualVerificationRequired) { }
    public record SafeFields(List<SafeLoan> loans, String requestedRepaymentOrder) { }
    public record SafeLoan(String maskedAccountNumber, java.math.BigDecimal balance,
                           java.time.LocalDate accelerationDate) { }
    public record SafeSignatureChecks(String debtorSignatureField, String thirdPartySignatureField) { }
}
