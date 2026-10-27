package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** MySQL/JPA metadata adapter. File bytes remain in C02FileStorage and only its opaque key is persisted. */
@Component
@ConditionalOnProperty(name="app.c02.document-port", havingValue="jpa")
class JpaDocumentPort implements DocumentPort {
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private final JpaDocumentFileRepository repository;
    JpaDocumentPort(JpaDocumentFileRepository repository) { this.repository=repository; }

    @Override @Transactional public DocumentMetadata register(DocumentRegistration command) {
        validate(command);
        long requiredId=requiredId(command); int version=repository.maxVersion(requiredId)+1;
        try { repository.clearLatest(requiredId); JpaDocumentFileEntity saved=repository.saveAndFlush(new JpaDocumentFileEntity(requiredId,version,command)); repository.updateStatus(requiredId,command.status().name()); return row(repository.metadataById(saved.id()).orElseThrow()); }
        catch(DataIntegrityViolationException conflict) { throw new DomainException("DOCUMENT_VERSION_CONFLICT","Document version changed concurrently.",true); }
    }
    @Override @Transactional(readOnly=true) public List<DocumentMetadata> findByCase(long caseId) { return repository.metadataByCase(caseId).stream().map(JpaDocumentPort::row).toList(); }
    @Override @Transactional(readOnly=true) public Optional<DocumentMetadata> findById(long documentFileId) { return repository.metadataById(documentFileId).map(JpaDocumentPort::row); }
    @Override @Transactional public void delete(long documentFileId) { repository.deleteById(documentFileId); }
    @Override @Transactional(readOnly=true) public C02IntakeService.DocumentStatus status(long caseId,String slot) {
        return repository.metadataByCase(caseId).stream().filter(row->slot(row).equals(slot)).max(java.util.Comparator.comparingInt(JpaDocumentFileRepository.DocumentRow::getVersion)).map(row->C02IntakeService.DocumentStatus.valueOf(row.getStatus())).orElse(C02IntakeService.DocumentStatus.NOT_SUBMITTED);
    }
    private long requiredId(DocumentRegistration command) { String[] parts=command.documentSlotKey().split(":",2); String personRef=parts.length==2?parts[1]:null; if("COMMON".equals(personRef)) personRef=null; List<Long> ids=repository.requiredIds(command.caseId(),parts[0],personRef); if(ids.isEmpty())throw new DomainException("DOCUMENT_SLOT_UNKNOWN","The document slot is not provisioned for this C-02 case."); if(ids.size()!=1)throw new DomainException("DOCUMENT_SLOT_AMBIGUOUS","The document slot is ambiguous."); return ids.get(0); }
    private static DocumentMetadata row(JpaDocumentFileRepository.DocumentRow r) { return new DocumentMetadata(r.getDocumentId(),r.getCaseId(),r.getCaseRevision(),slot(r),r.getVersion(),r.getOriginalName(),r.getContentType(),r.getSizeBytes(),r.getStorageKey(),C02IntakeService.DocumentStatus.valueOf(r.getStatus()),r.getDocumentType(),r.getChecksum(),r.getUploadedAt(),r.getUploadedBy()); }
    private static String slot(JpaDocumentFileRepository.DocumentRow r) { return r.getPersonRef()==null||r.getPersonRef().isBlank()?r.getDocumentType()+":COMMON":r.getDocumentType()+":"+r.getPersonRef(); }
    private static void validate(DocumentRegistration c) {
        if(c.sizeBytes()<1 || c.sizeBytes()>MAX_SIZE) throw new DomainException("FILE_TOO_LARGE","Document metadata exceeds the 10MB limit.");
        if(c.mimeType()==null || !(c.mimeType().equals("application/pdf")||c.mimeType().equals("image/png")||c.mimeType().equals("image/jpeg"))) throw new DomainException("UNSUPPORTED_MIME","Only PDF, PNG, and JPEG metadata is accepted.");
        if(c.status()==null || c.status()==C02IntakeService.DocumentStatus.NOT_SUBMITTED) throw new DomainException("INVALID_DOCUMENT_STATUS","Registered metadata must describe a submitted document.");
        if(c.storageReference()==null || !c.storageReference().matches("[0-9a-fA-F-]{36}\\.(pdf|png|jpg|jpeg)")) throw new DomainException("INVALID_STORAGE_KEY","Invalid file storage key.");
        if(c.uploadedAt()==null || c.documentType()==null || c.documentType().isBlank()) throw new DomainException("INVALID_DOCUMENT_METADATA","Document type and upload time are required.");
    }
}
