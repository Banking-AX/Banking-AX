package com.bankingax.task.c02;

import jakarta.persistence.*;
import java.time.Instant;

/** Maps the authoritative document_file table; three metadata columns await T1's Flyway assignment. */
@Entity
@Table(name="document_file")
class JpaDocumentFileEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="required_document_id",nullable=false) private long requiredDocumentId;
    @Column(nullable=false) private int version;
    @Column(name="is_latest",nullable=false) private boolean latest;
    @Column(name="original_name",nullable=false) private String originalName;
    @Column(nullable=false) private String extension;
    @Column(name="size_bytes",nullable=false) private long sizeBytes;
    @Column(name="storage_path",nullable=false) private String storageKey;
    @Column(name="content_type", length=100) private String contentType;
    @Column(name="checksum", columnDefinition="CHAR(64)") private String checksum;
    @Column(name="case_revision") private int caseRevision;
    @Column(name="uploaded_by",nullable=false) private long uploadedBy;
    @Column(name="uploaded_at",nullable=false) private Instant uploadedAt;
    protected JpaDocumentFileEntity() { }
    JpaDocumentFileEntity(long requiredDocumentId,int version,DocumentPort.DocumentRegistration c) {
        this.requiredDocumentId=requiredDocumentId; this.version=version; latest=true; originalName=c.fileName(); extension=extension(c.fileName());
        sizeBytes=c.sizeBytes(); storageKey=c.storageReference(); contentType=c.mimeType(); checksum=c.checksum(); caseRevision=c.caseRevision();
        uploadedBy=c.uploadedBy(); uploadedAt=c.uploadedAt();
    }
    static String extension(String fileName) { int dot=fileName==null?-1:fileName.lastIndexOf('.'); return dot<1?"":fileName.substring(dot+1).toLowerCase(java.util.Locale.ROOT); }
    long id(){return id;} long requiredDocumentId(){return requiredDocumentId;} int version(){return version;} String originalName(){return originalName;} String extension(){return extension;} long sizeBytes(){return sizeBytes;} String storageKey(){return storageKey;} String contentType(){return contentType;} String checksum(){return checksum;} int caseRevision(){return caseRevision;} long uploadedBy(){return uploadedBy;} Instant uploadedAt(){return uploadedAt;}
}
