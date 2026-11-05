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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class B01DocumentService {
    static final String DOCUMENT_TYPE = "B01_REPAYMENT_ORDER_APPLICATION";
    private final JdbcTemplate jdbc;
    private final B01FileStorage files;
    private final B01OcrPort ocr;
    private final B01DocumentImageAnalysisPort imageAnalysis;
    private final ObjectMapper json;
    private final B01ProgressService progress;
    private final TransactionTemplate transactions;

    B01DocumentService(ObjectProvider<JdbcTemplate> jdbc, B01FileStorage files,
                       B01OcrPort ocr, ObjectProvider<B01DocumentImageAnalysisPort> imageAnalysis,
                       ObjectMapper json, B01ProgressService progress,
                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc.getIfAvailable();
        this.files = files;
        this.ocr = ocr;
        this.imageAnalysis = imageAnalysis.getIfAvailable();
        this.json = json;
        this.progress = progress;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public UploadResult upload(long customerId, long workItemId, MultipartFile upload) {
        return upload(customerId, workItemId, "internal-" + java.util.UUID.randomUUID(), upload);
    }

    public UploadResult upload(long customerId, long workItemId, String requestId, MultipartFile upload) {
        requireJdbc();
        UploadResult duplicate = transactions.execute(status -> existingRequest(customerId, workItemId, requestId));
        if (duplicate != null) return duplicate;
        C02FileStorage.StoredFile stored = files.store(upload);
        PreparedFile prepared;
        try {
            prepared = transactions.execute(status -> prepareUpload(customerId, workItemId, stored));
        } catch (RuntimeException exception) {
            files.deleteQuietly(stored.storageKey());
            throw exception;
        }
        B01OcrPort.OcrResult extracted = analyze(requestId, prepared.id(), prepared.checksum(),
                prepared.storageKey(), prepared.originalName(), prepared.contentType());
        return transactions.execute(status -> persistAndComplete(customerId, workItemId, requestId,
                prepared, extracted));
    }

    /** Internal-only replay hook. The row lock and existence check prevent duplicate extraction rows. */
    UploadResult extractExisting(long customerId, long workItemId, long documentFileId) {
        requireJdbc();
        FileRow file = transactions.execute(status -> {
            requireOwnedActiveWorkItem(customerId, workItemId);
            return jdbc.query("""
                select df.id,df.version,df.checksum,df.storage_path,df.original_name,df.content_type
                  from document_file df
                  join required_document rd on rd.id=df.required_document_id
                 where df.id=? and rd.work_item_id=? and rd.document_type_code=?
                """, rs -> rs.next() ? new FileRow(rs.getLong(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)) : null,
                documentFileId, workItemId, DOCUMENT_TYPE);
        });
        if (file == null) throw unavailable();
        B01OcrPort.OcrResult saved = transactions.execute(status -> savedExtraction(file.id()));
        if (saved != null) return safeResult(file.id(), file.version(), saved);
        B01OcrPort.OcrResult extracted = analyze("document-" + file.id(), file.id(), file.checksum(),
                file.storageKey(), file.originalName(), file.contentType());
        return transactions.execute(status -> safeResult(file.id(), file.version(),
                persistOnceLocked(file.id(), extracted)));
    }

    private B01OcrPort.OcrResult analyze(String requestId, long documentFileId, String checksum, String storageKey,
                                         String originalName, String contentType) {
        B01OcrPort.OcrResult result;
        try {
            byte[] content = files.read(storageKey);
            result = ocr.extract(new B01OcrPort.ExtractRequest(documentFileId, DOCUMENT_TYPE, checksum,
                    originalName, contentType, content));
            if (imageAnalysis != null) {
                result = result.withVisualChecks(imageAnalysis.analyze(
                        new B01DocumentImageAnalysisPort.AnalysisRequest(requestId, originalName,
                                contentType, content)));
            }
        } catch (RuntimeException exception) {
            result = new B01OcrPort.OcrResult(DOCUMENT_TYPE, null, ocr.method(), "FAILED_NEEDS_STAFF",
                    new B01OcrPort.Fields(List.of(), null),
                    new B01OcrPort.SignatureChecks("NOT_VERIFIED", "NOT_VERIFIED"),
                    false, true, "OCR_UNAVAILABLE");
        }
        return result;
    }

    private PreparedFile prepareUpload(long customerId, long workItemId, C02FileStorage.StoredFile stored) {
        requireOwnedActiveWorkItem(customerId, workItemId);
        long requiredDocumentId = requiredDocument(workItemId);
        int version = nextVersion(requiredDocumentId);
        jdbc.update("update document_file set is_latest=false where required_document_id=? and is_latest=true",
                requiredDocumentId);
        long id = insertFile(requiredDocumentId, version, customerId, stored);
        jdbc.update("update required_document set status='UPLOADED' where id=?", requiredDocumentId);
        return new PreparedFile(id, version, stored.checksum(), stored.storageKey(),
                stored.originalFilename(), stored.contentType());
    }

    private UploadResult persistAndComplete(long customerId, long workItemId, String requestId, PreparedFile file,
                                            B01OcrPort.OcrResult result) {
        B01OcrPort.OcrResult persisted = persistOnceLocked(file.id(), result);
        progress.documentUploaded(customerId, workItemId, file.id(), file.version(), requestId);
        return safeResult(file.id(), file.version(), persisted);
    }

    private UploadResult existingRequest(long customerId, long workItemId, String requestId) {
        requireOwnedActiveWorkItem(customerId, workItemId);
        List<Long> ids = jdbc.query("""
                select cast(json_unquote(json_extract(wi.draft_data,'$.documentFileId')) as unsigned)
                  from work_item wi
                 where wi.id=? and json_unquote(json_extract(wi.draft_data,'$.documentUploadRequestId'))=?
                """, (rs, row) -> rs.getLong(1), workItemId, requestId);
        if (ids.isEmpty() || ids.get(0) <= 0) return null;
        List<FileRow> files = jdbc.query("""
                select df.id,df.version,df.checksum,df.storage_path,df.original_name,df.content_type
                  from document_file df join required_document rd on rd.id=df.required_document_id
                 where df.id=? and rd.work_item_id=? and df.deleted_at is null
                """, (rs, row) -> new FileRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6)), ids.get(0), workItemId);
        if (files.isEmpty()) return null;
        B01OcrPort.OcrResult extraction = savedExtraction(files.get(0).id());
        return extraction == null ? null : safeResult(files.get(0).id(), files.get(0).version(), extraction);
    }

    private B01OcrPort.OcrResult persistOnceLocked(long documentFileId, B01OcrPort.OcrResult result) {
        jdbc.queryForObject("select id from document_file where id=? for update", Long.class, documentFileId);
        B01OcrPort.OcrResult saved = savedExtraction(documentFileId);
        if (saved != null) return saved;
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

    private B01OcrPort.OcrResult savedExtraction(long documentFileId) {
        List<String> saved = jdbc.query("select extracted from document_extraction where document_file_id=? order by id",
                (rs, row) -> rs.getString(1), documentFileId);
        return saved.isEmpty() ? null : read(saved.get(0));
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
                safeVisualChecks(result.visualChecks()), result.authenticityVerified(),
                result.manualVerificationRequired());
    }

    private static SafeVisualChecks safeVisualChecks(B01OcrPort.VisualChecks checks) {
        if (checks == null) return null;
        return new SafeVisualChecks(checks.alignmentStatus(), checks.firstBox(), checks.secondBox(),
                checks.selectedOrder(), checks.debtorSignatureTrace(), checks.uncertain(),
                checks.manualVerificationRequired(), checks.failureReason());
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
    private record PreparedFile(long id, int version, String checksum, String storageKey,
                                String originalName, String contentType) { }

    public record UploadResult(long documentFileId, int version, String documentTypeCode,
                               String detectedType, String method, String status, SafeFields fields,
                               SafeSignatureChecks signatureChecks,
                               SafeVisualChecks visualChecks,
                               boolean authenticityVerified, boolean manualVerificationRequired) { }
    public record SafeFields(List<SafeLoan> loans, String requestedRepaymentOrder) { }
    public record SafeLoan(String maskedAccountNumber, java.math.BigDecimal balance,
                           java.time.LocalDate accelerationDate) { }
    public record SafeSignatureChecks(String debtorSignatureField, String thirdPartySignatureField) { }
    public record SafeVisualChecks(String alignmentStatus, String firstBox, String secondBox,
                                   String selectedOrder, String debtorSignatureTrace, boolean uncertain,
                                   boolean manualVerificationRequired, String failureReason) { }
}
