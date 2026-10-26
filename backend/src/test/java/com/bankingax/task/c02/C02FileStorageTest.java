package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class C02FileStorageTest {
    @TempDir Path temp;
    @Test void stores_private_pdf_with_checksum_and_reads_identical_bytes() throws Exception {
        byte[] content="%PDF-1.4\nsynthetic fixture".getBytes();
        var store=new C02FileStorage(temp.toString(),1024);
        var saved=store.store(new MockMultipartFile("file","inheritance.pdf","application/pdf",content));
        assertEquals("inheritance.pdf",saved.originalFilename()); assertEquals("application/pdf",saved.contentType()); assertEquals(64,saved.checksum().length());
        try(var input=store.open(saved.storageKey())) { assertArrayEquals(content,input.readAllBytes()); }
        assertFalse(Files.exists(temp.resolve(saved.storageKey()))); // storage key is not a client-visible path
    }
    @Test void rejects_extension_or_declared_mime_that_disagrees_with_signature() throws Exception {
        var store=new C02FileStorage(temp.toString(),1024);
        assertThrows(DomainException.class,()->store.store(new MockMultipartFile("file","bad.jpg","image/jpeg","%PDF-1.4".getBytes())));
        assertThrows(DomainException.class,()->store.store(new MockMultipartFile("file","empty.pdf","application/pdf",new byte[0])));
        try(var leftovers=Files.list(temp.resolve("tmp"))) { assertEquals(0,leftovers.count(),"rejected upload must not leave a .part file"); }
    }
    @Test void local_metadata_survives_adapter_restart_and_versions_same_slot() {
        var json=new ObjectMapper().findAndRegisterModules(); var first=new LocalFileDocumentPort(temp.toString(),json);
        var one=first.register(new DocumentPort.DocumentRegistration(77,0,"HEIR_IDENTITY_CARD:h1","one.pdf","application/pdf",1,"00000000-0000-0000-0000-000000000001.pdf",C02IntakeService.DocumentStatus.UPLOADED,"HEIR_IDENTITY_CARD","a".repeat(64),java.time.Instant.parse("2026-10-26T00:00:00Z"),7L));
        var two=first.register(new DocumentPort.DocumentRegistration(77,1,"HEIR_IDENTITY_CARD:h1","two.pdf","application/pdf",1,"00000000-0000-0000-0000-000000000002.pdf",C02IntakeService.DocumentStatus.NEEDS_SUPPLEMENT));
        var restarted=new LocalFileDocumentPort(temp.toString(),json);
        assertEquals(2,restarted.findByCase(77).size()); assertEquals(2,two.version()); assertEquals(one,restarted.findById(one.documentFileId()).orElseThrow()); assertEquals("HEIR_IDENTITY_CARD",one.documentType()); assertEquals("a".repeat(64),one.checksum()); assertEquals(7L,one.uploadedBy()); assertEquals(C02IntakeService.DocumentStatus.NEEDS_SUPPLEMENT,restarted.status(77,"HEIR_IDENTITY_CARD:h1"));
    }
}
