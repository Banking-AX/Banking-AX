package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Development-only metadata store; it never stores file content. */
@Component
@ConditionalOnProperty(name="app.c02.document-port", havingValue="inmemory", matchIfMissing=true)
public final class InMemoryDocumentPort implements DocumentPort {
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private final AtomicLong ids = new AtomicLong(9000);
    private final Map<Long, List<DocumentMetadata>> documents = new HashMap<>();

    @Override public synchronized DocumentMetadata register(DocumentRegistration command) {
        if (command.sizeBytes() < 0 || command.sizeBytes() > MAX_SIZE_BYTES)
            throw new DomainException("FILE_TOO_LARGE", "Document metadata exceeds the 10MB limit.");
        if (command.mimeType() == null || !(command.mimeType().equals("application/pdf") || command.mimeType().startsWith("image/")))
            throw new DomainException("UNSUPPORTED_MIME", "Only PDF and image document metadata is accepted.");
        if (command.status() == null || command.status() == C02IntakeService.DocumentStatus.NOT_SUBMITTED)
            throw new DomainException("INVALID_DOCUMENT_STATUS", "Registered metadata must describe a submitted document.");
        List<DocumentMetadata> caseDocuments = documents.computeIfAbsent(command.caseId(), ignored -> new ArrayList<>());
        int version = caseDocuments.stream().filter(d -> d.documentSlotKey().equals(command.documentSlotKey()))
            .mapToInt(DocumentMetadata::version).max().orElse(0) + 1;
        DocumentMetadata metadata = new DocumentMetadata(ids.incrementAndGet(), command.caseId(), command.caseRevision(),
            command.documentSlotKey(), version, command.fileName(), command.mimeType(), command.sizeBytes(),
            command.storageReference(), command.status(), command.documentType(), command.checksum(),
            command.uploadedAt(), command.uploadedBy());
        caseDocuments.add(metadata);
        return metadata;
    }

    @Override public synchronized List<DocumentMetadata> findByCase(long caseId) {
        return List.copyOf(documents.getOrDefault(caseId, List.of()));
    }
    @Override public synchronized java.util.Optional<DocumentMetadata> findById(long documentFileId) {
        return documents.values().stream().flatMap(List::stream).filter(d -> d.documentFileId()==documentFileId).findFirst();
    }
    @Override public synchronized void delete(long documentFileId) {
        documents.values().forEach(items -> items.removeIf(item -> item.documentFileId() == documentFileId));
        documents.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @Override public synchronized C02IntakeService.DocumentStatus status(long caseId, String documentSlotKey) {
        return documents.getOrDefault(caseId, List.of()).stream().filter(d -> d.documentSlotKey().equals(documentSlotKey))
            .max(Comparator.comparingInt(DocumentMetadata::version)).map(DocumentMetadata::status)
            .orElse(C02IntakeService.DocumentStatus.NOT_SUBMITTED);
    }
}
