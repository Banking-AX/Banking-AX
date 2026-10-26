package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-disk metadata persistence for the file-storage PoC; this is not the MySQL document_file adapter. */
@Component
@ConditionalOnProperty(name="app.c02.document-port",havingValue="local")
public final class LocalFileDocumentPort implements DocumentPort {
    private final Path index; private final ObjectMapper json; private final Map<Long,List<DocumentMetadata>> values=new HashMap<>(); private final AtomicLong ids=new AtomicLong(10000);
    public LocalFileDocumentPort(@Value("${app.c02.file-storage-root:./var/c02-documents}") String root,ObjectMapper json) { this.index=Paths.get(root).toAbsolutePath().normalize().resolve("metadata.json"); this.json=json; load(); }
    @Override public synchronized DocumentMetadata register(DocumentRegistration c) {
        if(c.sizeBytes()<1||c.sizeBytes()>10L*1024*1024)throw new DomainException("FILE_TOO_LARGE","Document metadata exceeds the 10MB limit.");
        if(c.mimeType()==null||!(c.mimeType().equals("application/pdf")||c.mimeType().equals("image/png")||c.mimeType().equals("image/jpeg")))throw new DomainException("UNSUPPORTED_MIME","Only PDF, PNG, and JPEG metadata is accepted.");
        if(c.status()==null||c.status()==C02IntakeService.DocumentStatus.NOT_SUBMITTED)throw new DomainException("INVALID_DOCUMENT_STATUS","Registered metadata must describe a submitted document.");
        List<DocumentMetadata> docs=values.computeIfAbsent(c.caseId(),ignored->new ArrayList<>()); int version=docs.stream().filter(d->d.documentSlotKey().equals(c.documentSlotKey())).mapToInt(DocumentMetadata::version).max().orElse(0)+1;
        DocumentMetadata result=new DocumentMetadata(ids.incrementAndGet(),c.caseId(),c.caseRevision(),c.documentSlotKey(),version,c.fileName(),c.mimeType(),c.sizeBytes(),c.storageReference(),c.status(),c.documentType(),c.checksum(),c.uploadedAt(),c.uploadedBy()); docs.add(result);
        try { persist(); return result; } catch (RuntimeException error) { docs.remove(result); if(docs.isEmpty()) values.remove(c.caseId()); throw error; }
    }
    @Override public synchronized List<DocumentMetadata> findByCase(long caseId){return List.copyOf(values.getOrDefault(caseId,List.of()));}
    @Override public synchronized Optional<DocumentMetadata> findById(long id){return values.values().stream().flatMap(List::stream).filter(d->d.documentFileId()==id).findFirst();}
    @Override public synchronized void delete(long id){
        Long caseId=null; DocumentMetadata removed=null;
        for(var entry:values.entrySet()) { for(var item:entry.getValue()) if(item.documentFileId()==id) { caseId=entry.getKey(); removed=item; break; } if(removed!=null) break; }
        if(removed==null) return;
        List<DocumentMetadata> docs=values.get(caseId); docs.remove(removed);
        if(docs.isEmpty()) values.remove(caseId);
        try { persist(); } catch(RuntimeException error) { values.computeIfAbsent(caseId, ignored->new ArrayList<>()).add(removed); throw error; }
    }
    @Override public synchronized C02IntakeService.DocumentStatus status(long caseId,String slot){return values.getOrDefault(caseId,List.of()).stream().filter(d->d.documentSlotKey().equals(slot)).max(Comparator.comparingInt(DocumentMetadata::version)).map(DocumentMetadata::status).orElse(C02IntakeService.DocumentStatus.NOT_SUBMITTED);}
    private void load(){try{Files.createDirectories(index.getParent()); if(!Files.exists(index))return; List<DocumentMetadata> loaded=json.readValue(index.toFile(),new TypeReference<>(){}); for(var d:loaded){values.computeIfAbsent(d.caseId(),ignored->new ArrayList<>()).add(d);ids.set(Math.max(ids.get(),d.documentFileId()));}}catch(Exception e){throw new IllegalStateException("Local document metadata index is unreadable.",e);}}
    private void persist(){try{Path temp=Files.createTempFile(index.getParent(),"metadata-",".json"); json.writeValue(temp.toFile(),values.values().stream().flatMap(List::stream).toList()); try{Files.move(temp,index,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,index,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new DomainException("DOCUMENT_METADATA_STORE_FAILED","Document metadata persistence failed.",true);}}
}
