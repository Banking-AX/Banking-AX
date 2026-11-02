package com.bankingax.task.c02;

import com.bankingax.core.api.DomainException;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Private local PoC store. Keys, never absolute paths, are persisted in DocumentPort. */
@Component
public final class C02FileStorage {
    private final Path root; private final long maxBytes;
    public C02FileStorage(@Value("${app.c02.file-storage-root:./var/c02-documents}") String root,@Value("${app.c02.max-file-bytes:10485760}") long maxBytes) { this.root=Paths.get(root).toAbsolutePath().normalize(); this.maxBytes=maxBytes; }
    public StoredFile store(MultipartFile upload) {
        if(upload==null || upload.isEmpty()) throw new DomainException("EMPTY_FILE","A non-empty document file is required.");
        if(upload.getSize()>maxBytes) throw new DomainException("FILE_TOO_LARGE","Document exceeds the configured size limit.");
        String original=Paths.get(upload.getOriginalFilename()==null?"":upload.getOriginalFilename()).getFileName().toString();
        String ext=extension(original); if(!(ext.equals("pdf")||ext.equals("png")||ext.equals("jpg")||ext.equals("jpeg"))) throw new DomainException("UNSUPPORTED_FILE_TYPE","Only PDF, PNG, and JPEG files are allowed.");
        Path temporary=null;
        try {
            Files.createDirectories(root.resolve("tmp")); Files.createDirectories(root.resolve("files"));
            temporary=Files.createTempFile(root.resolve("tmp"),"upload-",".part"); MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=0;
            try(InputStream in=upload.getInputStream(); OutputStream out=Files.newOutputStream(temporary)) { byte[] buffer=new byte[8192]; for(int read;(read=in.read(buffer))!=-1;) { count+=read; if(count>maxBytes) throw new DomainException("FILE_TOO_LARGE","Document exceeds the configured size limit."); digest.update(buffer,0,read); out.write(buffer,0,read); } }
            String detected=detectedType(temporary); if(!matches(ext,upload.getContentType(),detected)) throw new DomainException("FILE_CONTENT_TYPE_MISMATCH","File extension, MIME type, and binary signature must agree.");
            String key=UUID.randomUUID()+"."+ext; Path target=root.resolve("files").resolve(key).normalize(); if(!target.startsWith(root.resolve("files"))) throw new DomainException("INVALID_STORAGE_KEY","Invalid file storage key.");
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); } catch(AtomicMoveNotSupportedException ignored) { Files.move(temporary,target); }
            temporary=null; // ownership moved into the private files directory
            return new StoredFile(key,original,detected,count,java.util.HexFormat.of().formatHex(digest.digest()));
        } catch(DomainException e) { throw e; } catch(Exception e) { throw new DomainException("DOCUMENT_STORAGE_FAILED","Document storage failed.",true); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) { } }
    }
    public InputStream open(String key) { try { Path file=resolve(key); return Files.newInputStream(file,StandardOpenOption.READ); } catch(IOException e) { throw new DomainException("DOCUMENT_NOT_FOUND","Stored document file was not found."); } }
    /** Reads a bounded file from the private store for an explicitly selected OCR provider. */
    public byte[] readForOcr(String key,long limitBytes) {
        if(limitBytes<1) throw new DomainException("OCR_FILE_LIMIT_INVALID","OCR file limit is invalid.");
        try(InputStream in=open(key); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; long total=0;
            for(int read;(read=in.read(buffer))!=-1;) {
                total+=read;
                if(total>limitBytes) throw new DomainException("OCR_FILE_TOO_LARGE","Document exceeds the OCR size limit.");
                out.write(buffer,0,read);
            }
            return out.toByteArray();
        } catch(DomainException e) { throw e; }
        catch(IOException e) { throw new DomainException("OCR_FILE_READ_FAILED","Stored document could not be read for OCR.",true); }
    }
    public void deleteQuietly(String key) { try { Files.deleteIfExists(resolve(key)); } catch(Exception ignored) { } }
    private Path resolve(String key) { if(key==null || !key.matches("[0-9a-fA-F-]{36}\\.(pdf|png|jpg|jpeg)")) throw new DomainException("INVALID_STORAGE_KEY","Invalid file storage key."); Path file=root.resolve("files").resolve(key).normalize(); if(!file.startsWith(root.resolve("files"))) throw new DomainException("INVALID_STORAGE_KEY","Invalid file storage key."); return file; }
    private static String extension(String file) { int dot=file.lastIndexOf('.'); return dot<1?"":file.substring(dot+1).toLowerCase(Locale.ROOT); }
    private static boolean matches(String ext,String declared,String detected) { String expected=ext.equals("pdf")?"application/pdf":ext.equals("png")?"image/png":"image/jpeg"; return expected.equals(detected) && (declared==null||declared.isBlank()||expected.equals(declared)); }
    private static String detectedType(Path file) throws IOException { byte[] h; try(InputStream in=Files.newInputStream(file)){h=in.readNBytes(8);} if(h.length>=5&&h[0]=='%'&&h[1]=='P'&&h[2]=='D'&&h[3]=='F'&&h[4]=='-')return "application/pdf"; if(h.length>=8&&h[0]==(byte)0x89&&h[1]==0x50&&h[2]==0x4e&&h[3]==0x47)return "image/png"; if(h.length>=3&&h[0]==(byte)0xff&&h[1]==(byte)0xd8&&h[2]==(byte)0xff)return "image/jpeg"; throw new DomainException("UNSUPPORTED_FILE_CONTENT","File binary signature is not PDF, PNG, or JPEG."); }
    public record StoredFile(String storageKey,String originalFilename,String contentType,long fileSize,String checksum) {}
}
