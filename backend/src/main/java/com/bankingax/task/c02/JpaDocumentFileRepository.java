package com.bankingax.task.c02;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface JpaDocumentFileRepository extends JpaRepository<JpaDocumentFileEntity,Long> {
    @Query("select coalesce(max(f.version),0) from JpaDocumentFileEntity f where f.requiredDocumentId=:requiredId") int maxVersion(@Param("requiredId") long requiredId);
    @Modifying @Query("update JpaDocumentFileEntity f set f.latest=false where f.requiredDocumentId=:requiredId and f.latest=true") int clearLatest(@Param("requiredId") long requiredId);
    @Query(value="""
        select rd.id from required_document rd join work_item wi on wi.id=rd.work_item_id join service_case sc on sc.work_item_id=wi.id
        left join heir h on h.id=rd.heir_id where sc.id=:caseId and rd.document_type_code=:type
        and ((:personRef is null and rd.heir_id is null) or h.person_ref=:personRef)
        """,nativeQuery=true) List<Long> requiredIds(@Param("caseId") long caseId,@Param("type") String type,@Param("personRef") String personRef);
    @Modifying @Query(value="update required_document set status=:status where id=:requiredId",nativeQuery=true) int updateStatus(@Param("requiredId") long requiredId,@Param("status") String status);
    @Query(value="""
        select df.id as documentId, sc.id as caseId, df.case_revision as caseRevision, rd.document_type_code as documentType,
          h.person_ref as personRef, df.version as version, df.original_name as originalName, df.content_type as contentType,
          df.size_bytes as sizeBytes, df.storage_path as storageKey, rd.status as status, df.checksum as checksum,
          df.uploaded_at as uploadedAt, df.uploaded_by as uploadedBy
        from document_file df join required_document rd on rd.id=df.required_document_id join work_item wi on wi.id=rd.work_item_id
          join service_case sc on sc.work_item_id=wi.id left join heir h on h.id=rd.heir_id
        where df.id=:documentId
        """,nativeQuery=true) Optional<DocumentRow> metadataById(@Param("documentId") long documentId);
    @Query(value="""
        select df.id as documentId, sc.id as caseId, df.case_revision as caseRevision, rd.document_type_code as documentType,
          h.person_ref as personRef, df.version as version, df.original_name as originalName, df.content_type as contentType,
          df.size_bytes as sizeBytes, df.storage_path as storageKey, rd.status as status, df.checksum as checksum,
          df.uploaded_at as uploadedAt, df.uploaded_by as uploadedBy
        from document_file df join required_document rd on rd.id=df.required_document_id join work_item wi on wi.id=rd.work_item_id
          join service_case sc on sc.work_item_id=wi.id left join heir h on h.id=rd.heir_id
        where sc.id=:caseId and df.deleted_at is null order by rd.document_type_code,h.person_ref,df.version
        """,nativeQuery=true) List<DocumentRow> metadataByCase(@Param("caseId") long caseId);
    interface DocumentRow { long getDocumentId(); long getCaseId(); int getCaseRevision(); String getDocumentType(); String getPersonRef(); int getVersion(); String getOriginalName(); String getContentType(); long getSizeBytes(); String getStorageKey(); String getStatus(); String getChecksum(); java.time.Instant getUploadedAt(); long getUploadedBy(); }
}
