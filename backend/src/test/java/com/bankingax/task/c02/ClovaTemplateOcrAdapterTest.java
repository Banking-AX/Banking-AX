package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class ClovaTemplateOcrAdapterTest {
    private static final int TEMPLATE_ID=24680;
    private static final ObjectMapper JSON=new ObjectMapper();
    private HttpServer server;

    @AfterEach void stopServer() { if(server!=null) server.stop(0); }

    @Test void sends_only_allowlisted_pdf_as_v2_template_request_and_maps_fields(@TempDir Path temp) throws Exception {
        AtomicReference<String> requestBody=new AtomicReference<>(); AtomicReference<String> secret=new AtomicReference<>();
        String response=response("SUCCESS",TEMPLATE_ID,"Applicant name","Test Person");
        startServer(200,response,requestBody,secret,null);
        byte[] pdf=pdf(1); C02FileStorage storage=storage(temp,pdf);
        ClovaTemplateOcrAdapter adapter=adapter(serverUrl(),"test-secret",String.valueOf(TEMPLATE_ID),storage);

        OcrPort.OcrResult result=adapter.extract(new OcrPort.ExtractRequest(42,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));

        assertEquals("CLOVA_TEMPLATE",result.method()); assertEquals("SUCCEEDED",result.status());
        assertEquals(true,result.fields().get("manualVerificationRequired"));
        assertEquals("CLOVA_TEMPLATE_REFERENCE_ONLY",result.fields().get("source"));
        assertEquals("test-secret",secret.get());
        var body=JSON.readTree(requestBody.get());
        assertEquals("V2",body.path("version").asText()); assertEquals("ko",body.path("lang").asText());
        var image=body.path("images").get(0);
        assertEquals("pdf",image.path("format").asText()); assertEquals(TEMPLATE_ID,image.path("templateIds").get(0).asInt());
        assertArrayEquals(pdf,Base64.getDecoder().decode(image.path("data").asText()));
        var fields=(java.util.List<?>)result.fields().get("fields");
        assertEquals("Applicant name",((Map<?,?>)fields.get(0)).get("name"));
    }

    @Test void non_allowlisted_document_does_not_read_or_send_file(@TempDir Path temp) throws Exception {
        AtomicInteger calls=new AtomicInteger(); startServer(200,"{}",null,null,calls);
        var adapter=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),new C02FileStorage(temp.toString(),1024));
        var result=adapter.extract(new OcrPort.ExtractRequest(2,"DECEDENT_BASIC_CERTIFICATE","not-a-storage-key"));
        assertEquals("NONE",result.method()); assertEquals("NOT_APPLICABLE",result.status()); assertEquals(0,calls.get());
    }

    @Test void missing_configuration_is_a_manual_review_failure_without_http_call(@TempDir Path temp) throws Exception {
        AtomicInteger calls=new AtomicInteger(); startServer(200,"{}",null,null,calls);
        var adapter=adapter("","",String.valueOf(TEMPLATE_ID),new C02FileStorage(temp.toString(),1024));
        var result=adapter.extract(new OcrPort.ExtractRequest(3,"ABSENT_HEIR_POWER_OF_ATTORNEY","unused.pdf"));
        assertEquals("CLOVA_TEMPLATE",result.method()); assertEquals("FAILED_NEEDS_STAFF",result.status());
        assertEquals("CLOVA_CONFIGURATION_MISSING",result.failureReason()); assertEquals(0,calls.get());
    }

    @Test void missing_document_template_mapping_does_not_call_provider(@TempDir Path temp) throws Exception {
        AtomicInteger calls=new AtomicInteger(); startServer(200,"{}",null,null,calls);
        var adapter=adapter(serverUrl(),"secret","",new C02FileStorage(temp.toString(),1024));
        var result=adapter.extract(new OcrPort.ExtractRequest(32,"ABSENT_HEIR_POWER_OF_ATTORNEY","unused.pdf"));
        assertEquals("CLOVA_TEMPLATE_NOT_CONFIGURED",result.failureReason()); assertEquals(0,calls.get());
    }

    @Test void timeout_is_recorded_as_manual_review_failure(@TempDir Path temp) throws Exception {
        byte[] pdf=pdf(1); C02FileStorage storage=storage(temp,pdf);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/infer",exchange->{try { Thread.sleep(1800); byte[] b="{}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,b.length); exchange.getResponseBody().write(b); } catch(Exception ignored) { } finally { exchange.close(); }});
        server.start();
        var result=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage,10_000_000,5,1)
            .extract(new OcrPort.ExtractRequest(31,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("CLOVA_TIMEOUT",result.failureReason()); assertEquals("FAILED_NEEDS_STAFF",result.status());
    }

    @Test void wrong_template_and_recognition_failure_never_return_extracted_values(@TempDir Path temp) throws Exception {
        byte[] pdf=pdf(1); C02FileStorage storage=storage(temp,pdf);
        startServer(200,response("SUCCESS",TEMPLATE_ID+1,"private field","do not return"),null,null,null);
        var mismatch=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage)
            .extract(new OcrPort.ExtractRequest(4,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("CLOVA_TEMPLATE_MISMATCH",mismatch.failureReason()); assertFalse(mismatch.fields().containsKey("fields"));
        stopServer();
        startServer(200,response("FAILURE",TEMPLATE_ID,"private field","do not return"),null,null,null);
        var failed=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage)
            .extract(new OcrPort.ExtractRequest(4,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("CLOVA_INFERENCE_FAILED",failed.failureReason()); assertFalse(failed.fields().containsKey("fields"));
    }

    @Test void http_error_timeout_oversized_and_more_than_five_pages_are_safe_failures(@TempDir Path temp) throws Exception {
        byte[] pdf=pdf(1); C02FileStorage storage=storage(temp,pdf);
        startServer(503,"internal details must not escape",null,null,null);
        var http=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage)
            .extract(new OcrPort.ExtractRequest(5,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("CLOVA_HTTP_ERROR_503",http.failureReason());
        stopServer(); startServer(200,"{}",null,null,null);
        var tooLarge=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage,1,5)
            .extract(new OcrPort.ExtractRequest(5,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("OCR_FILE_TOO_LARGE",tooLarge.failureReason());
        var tooManyPages=adapter(serverUrl(),"secret",String.valueOf(TEMPLATE_ID),storage(temp,pdf(6)),10_000_000,5)
            .extract(new OcrPort.ExtractRequest(6,"ABSENT_HEIR_POWER_OF_ATTORNEY",storedKey));
        assertEquals("OCR_PDF_PAGE_LIMIT_EXCEEDED",tooManyPages.failureReason());
        assertEquals("FAILED_NEEDS_STAFF",tooManyPages.status());
    }

    private static String storedKey;
    private static C02FileStorage storage(Path temp,byte[] bytes) {
        C02FileStorage store=new C02FileStorage(temp.toString(),10_000_000);
        storedKey=store.store(new MockMultipartFile("file","official.pdf","application/pdf",bytes)).storageKey();
        return store;
    }
    private ClovaTemplateOcrAdapter adapter(String url,String secret,String template,C02FileStorage storage) {
        return adapter(url,secret,template,storage,10_000_000,5);
    }
    private ClovaTemplateOcrAdapter adapter(String url,String secret,String template,C02FileStorage storage,long maxBytes,int pages) {
        return adapter(url,secret,template,storage,maxBytes,pages,2);
    }
    private ClovaTemplateOcrAdapter adapter(String url,String secret,String template,C02FileStorage storage,long maxBytes,int pages,int timeoutSeconds) {
        ClovaOcrProperties p=new ClovaOcrProperties(); p.setInvokeUrl(url); p.setSecret(secret); p.setTimeoutSeconds(timeoutSeconds);
        p.setMaxPdfBytes(maxBytes); p.setMaxPdfPages(pages); p.setTemplateIds(Map.of("ABSENT_HEIR_POWER_OF_ATTORNEY",template));
        return new ClovaTemplateOcrAdapter(p,storage,JSON);
    }
    private void startServer(int status,String response,AtomicReference<String> body,AtomicReference<String> secret,AtomicInteger calls) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/infer",exchange->{
            if(calls!=null) calls.incrementAndGet();
            if(body!=null) body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if(secret!=null) secret.set(exchange.getRequestHeaders().getFirst("X-OCR-SECRET"));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); server.start();
    }
    private String serverUrl(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/infer";}
    private static String response(String status,int template,String field,String text) {
        try { return JSON.writeValueAsString(Map.of("version","V2","images",java.util.List.of(Map.of("inferResult",status,
            "matchedTemplate",Map.of("id",template,"name","Official delegation form"),"fields",java.util.List.of(Map.of("name",field,"inferText",text,"inferConfidence",0.98)))))); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static byte[] pdf(int pages) throws Exception {
        try(PDDocument document=new PDDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) document.addPage(new PDPage());
            document.save(out); return out.toByteArray();
        }
    }
}
