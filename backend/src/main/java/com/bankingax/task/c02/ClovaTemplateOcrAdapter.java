package com.bankingax.task.c02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** CLOVA Template OCR adapter. The caller must enforce the persisted official-form policy. */
@Component
@ConditionalOnProperty(name="app.c02.ocr-provider",havingValue="clova")
public final class ClovaTemplateOcrAdapter implements OcrPort {
    public static final String ALLOWED_DOCUMENT_TYPE = "ABSENT_HEIR_POWER_OF_ATTORNEY";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ClovaOcrProperties properties;
    private final C02FileStorage storage;
    private final ObjectMapper json;

    public ClovaTemplateOcrAdapter(ClovaOcrProperties properties,C02FileStorage storage,ObjectMapper json) {
        this.properties=properties; this.storage=storage; this.json=json;
    }

    @Override public OcrResult extract(ExtractRequest request) {
        if(!ALLOWED_DOCUMENT_TYPE.equals(request.documentType()))
            return notApplicable();
        final int templateId;
        final URI endpoint;
        try {
            if(properties.getInvokeUrl().isBlank() || properties.getSecret().isBlank())
                return failed("CLOVA_CONFIGURATION_MISSING");
            endpoint=validatedEndpoint(properties.getInvokeUrl());
            if(properties.getTimeoutSeconds()<1 || properties.getTimeoutSeconds()>60
                    || properties.getMaxPdfBytes()<1 || properties.getMaxPdfBytes()>50L*1024*1024
                    || properties.getMaxPdfPages()<1 || properties.getMaxPdfPages()>5)
                return failed("CLOVA_CONFIGURATION_INVALID");
            String configured=properties.getTemplateIds().get(ALLOWED_DOCUMENT_TYPE);
            if(configured==null || configured.isBlank()) return failed("CLOVA_TEMPLATE_NOT_CONFIGURED");
            templateId=Integer.parseInt(configured);
            if(templateId<=0) return failed("CLOVA_TEMPLATE_NOT_CONFIGURED");
        } catch(IllegalArgumentException exception) {
            return failed("CLOVA_CONFIGURATION_INVALID");
        }

        try {
            byte[] pdf=storage.readForOcr(request.storageReference(),properties.getMaxPdfBytes());
            validatePdf(pdf,properties.getMaxPdfPages());
            String requestId=UUID.randomUUID().toString();
            Map<String,Object> body=Map.of(
                "version","V2", "requestId",requestId, "timestamp",System.currentTimeMillis(), "lang","ko",
                "images",List.of(Map.of("format","pdf","name","c02-document-"+request.documentFileId(),
                    "data",Base64.getEncoder().encodeToString(pdf),"templateIds",List.of(templateId)))
            );
            byte[] requestBody=json.writeValueAsBytes(body);
            HttpRequest httpRequest=HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type","application/json")
                .header("X-OCR-SECRET",properties.getSecret())
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            HttpResponse<InputStream> response=HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build()
                .send(httpRequest,HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody=readResponse(response.body());
            if(response.statusCode()<200 || response.statusCode()>=300) return failed("CLOVA_HTTP_ERROR_"+response.statusCode());
            return parseResponse(responseBody,templateId);
        } catch(PdfLimitException limit) { return failed(limit.reason); }
        catch(com.bankingax.core.api.DomainException storageError) { return failed(storageError.code()); }
        catch(java.net.http.HttpTimeoutException timeout) { return failed("CLOVA_TIMEOUT"); }
        catch(Exception exception) { return failed("CLOVA_REQUEST_FAILED"); }
    }

    private OcrResult parseResponse(byte[] body,int expectedTemplateId) {
        try {
            JsonNode root=json.readTree(body);
            if(!"V2".equals(root.path("version").asText())) return failed("CLOVA_INVALID_RESPONSE_VERSION");
            JsonNode images=root.path("images");
            if(!images.isArray() || images.size()!=1) return failed("CLOVA_INVALID_RESPONSE_IMAGES");
            JsonNode image=images.get(0);
            if(!"SUCCESS".equals(image.path("inferResult").asText())) return failed("CLOVA_INFERENCE_FAILED");
            JsonNode matched=image.path("matchedTemplate");
            if(!matched.path("id").canConvertToInt() || matched.path("id").asInt()!=expectedTemplateId)
                return failed("CLOVA_TEMPLATE_MISMATCH");
            JsonNode sourceFields=image.path("fields");
            if(!sourceFields.isArray()) return failed("CLOVA_FIELDS_MISSING");
            List<Map<String,Object>> fields=new ArrayList<>();
            for(JsonNode field:sourceFields) {
                String name=field.path("name").asText("").trim();
                if(name.isEmpty()) return failed("CLOVA_FIELD_NAME_MISSING");
                Map<String,Object> value=new LinkedHashMap<>();
                value.put("text",field.path("inferText").asText(""));
                if(field.path("inferConfidence").isNumber()) value.put("confidence",field.path("inferConfidence").doubleValue());
                if(field.path("type").isTextual()) value.put("type",field.path("type").asText());
                if(field.path("checked").isBoolean()) value.put("checked",field.path("checked").booleanValue());
                fields.add(Map.of("name",name,"value",value));
            }
            Map<String,Object> extracted=new LinkedHashMap<>();
            extracted.put("templateId",expectedTemplateId);
            extracted.put("templateName",matched.path("name").asText(""));
            extracted.put("fields",fields);
            extracted.put("manualVerificationRequired",true);
            extracted.put("source", "CLOVA_TEMPLATE_REFERENCE_ONLY");
            return new OcrResult("CLOVA_TEMPLATE","SUCCEEDED",extracted,null);
        } catch(Exception exception) { return failed("CLOVA_INVALID_RESPONSE"); }
    }

    private byte[] readResponse(InputStream body) throws Exception {
        try(body; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int total=0;
            for(int count;(count=body.read(buffer))!=-1;) {
                total+=count;
                if(total>MAX_RESPONSE_BYTES) throw new IllegalArgumentException("response too large");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        }
    }

    private static void validatePdf(byte[] bytes,int maxPages) throws Exception {
        if(bytes.length<8 || bytes[0]!='%' || bytes[1]!='P' || bytes[2]!='D' || bytes[3]!='F' || bytes[4]!='-')
            throw new PdfLimitException("OCR_INPUT_NOT_PDF");
        try(PDDocument document=Loader.loadPDF(bytes)) {
            if(document.isEncrypted()) throw new PdfLimitException("OCR_ENCRYPTED_PDF_UNSUPPORTED");
            if(document.getNumberOfPages()<1) throw new PdfLimitException("OCR_EMPTY_PDF");
            if(document.getNumberOfPages()>maxPages) throw new PdfLimitException("OCR_PDF_PAGE_LIMIT_EXCEEDED");
        }
    }

    private static URI validatedEndpoint(String value) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException("missing endpoint");
        URI uri=URI.create(value);
        boolean loopback="localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if(!uri.isAbsolute() || uri.getHost()==null || uri.getUserInfo()!=null
                || !("https".equalsIgnoreCase(uri.getScheme()) || loopback && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("endpoint must use HTTPS");
        return uri;
    }
    private static OcrResult notApplicable() { return new OcrResult("NONE","NOT_APPLICABLE",Map.of(),null); }
    private static OcrResult failed(String reason) { return new OcrResult("CLOVA_TEMPLATE","FAILED_NEEDS_STAFF",Map.of("manualVerificationRequired",true),reason); }
    private static final class PdfLimitException extends Exception {
        private final String reason;
        private PdfLimitException(String reason) { this.reason=reason; }
    }
}
