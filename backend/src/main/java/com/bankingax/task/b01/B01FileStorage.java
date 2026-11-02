package com.bankingax.task.b01;

import com.bankingax.task.c02.C02FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** B-01 private file store using the established C-02 signature, MIME and path validation. */
@Component
final class B01FileStorage {
    private final C02FileStorage delegate;

    B01FileStorage(@Value("${app.b01.file-storage-root:./var/b01-documents}") String root,
                   @Value("${app.b01.max-file-bytes:10485760}") long maxBytes) {
        this.delegate = new C02FileStorage(root, maxBytes);
    }

    C02FileStorage.StoredFile store(MultipartFile file) {
        return delegate.store(file);
    }

    void deleteQuietly(String storageKey) {
        delegate.deleteQuietly(storageKey);
    }
}
