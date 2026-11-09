package com.bankingax.task.c02;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** C-02's CLOVA Template boundary. It returns extraction facts only, never an approval. */
@Component
@ConditionalOnProperty(name = "app.c02.ocr-provider", havingValue = "clova")
public final class ClovaOcrHttpAdapter implements OcrPort {
    private static final int DELEGATOR_ROWS = 5;
    private final URI invokeUrl; private final String secret; private final long templateId;
    private final Duration timeout; private final ObjectMapper json; private final HttpClient http;

    @Autowired public ClovaOcrHttpAdapter(@Value("${app.c02.clova.invoke-url:}") String url,
                               @Value("${app.c02.clova.secret:}") String secret,
                               @Value("${app.c02.clova.template-id:}") String template,
                               @Value("${app.c02.clova.connect-timeout-seconds:3}") long connectTimeout,
                               @Value("${app.c02.clova.read-timeout-seconds:15}") long readTimeout,
                               ObjectMapper json) { this(url, secret, template, Duration.ofSeconds(connectTimeout), Duration.ofSeconds(readTimeout), json, null); }
    ClovaOcrHttpAdapter(String url,String secret,String template,Duration connect,Duration timeout,ObjectMapper json,HttpClient client) {
        try { invokeUrl=URI.create(required(url,"invoke URL")); templateId=Long.parseLong(required(template,"template ID")); }
        catch(RuntimeException e) { throw new IllegalStateException("C-02 CLOVA OCR configuration is invalid.",e); }
        if(!invokeUrl.isAbsolute()||templateId<=0||timeout.isZero()||timeout.isNegative()) throw new IllegalStateException("C-02 CLOVA OCR configuration is invalid.");
        this.secret=required(secret,"secret"); this.timeout=timeout; this.json=json; this.http=client==null?HttpClient.newBuilder().connectTimeout(connect).build():client;
    }
    @Override public OcrResult extract(ExtractRequest request) {
        try { String boundary="banking-ax-c02-"+UUID.randomUUID();
            HttpRequest call=HttpRequest.newBuilder(invokeUrl).timeout(timeout).header("Content-Type","multipart/form-data; boundary="+boundary).header("X-OCR-SECRET",secret).POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary,request))).build();
            HttpResponse<String> response=http.send(call,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()==401||response.statusCode()==403)return failed("CLOVA_AUTHENTICATION_FAILED");
            if(response.statusCode()>=400&&response.statusCode()<500)return failed("CLOVA_REQUEST_REJECTED");
            if(response.statusCode()>=500)return failed("CLOVA_UNAVAILABLE");
            return response.statusCode()>=200&&response.statusCode()<300?parse(response.body()):failed("CLOVA_UNEXPECTED_RESPONSE");
        } catch(HttpTimeoutException e){return failed("CLOVA_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();return failed("CLOVA_INTERRUPTED");}
        catch(Exception e){return failed("CLOVA_UNAVAILABLE");}
    }
    private byte[] multipart(String boundary,ExtractRequest request)throws Exception{
        Map<String,Object> image=new LinkedHashMap<>(); image.put("format",format(request.contentType(),request.fileName())); image.put("name","c02-power-of-attorney"); image.put("templateIds",List.of(templateId));
        Map<String,Object> message=new LinkedHashMap<>(); message.put("version","V2"); message.put("requestId",UUID.randomUUID().toString()); message.put("timestamp",System.currentTimeMillis()); message.put("lang","ko"); message.put("images",List.of(image));
        ByteArrayOutputStream out=new ByteArrayOutputStream(); part(out,boundary,"message","application/json; charset=UTF-8",json.writeValueAsBytes(message),null); part(out,boundary,"file",request.contentType(),request.content(),"c02-power-of-attorney."+format(request.contentType(),request.fileName())); out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8)); return out.toByteArray();
    }
    private static void part(ByteArrayOutputStream out,String boundary,String name,String type,byte[] value,String file)throws Exception{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\""+(file==null?"":"; filename=\""+file+"\"")+"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(value);out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
    private OcrResult parse(String body){try{
        JsonNode images=json.readTree(body).path("images");if(!images.isArray()||images.size()!=1)return failed("CLOVA_IMAGE_RESULT_INVALID"); JsonNode image=images.get(0);String infer=image.path("inferResult").asText("");long matched=image.path("matchedTemplate").path("id").asLong(-1);
        if(!"SUCCESS".equals(infer))return failed("CLOVA_INFERENCE_FAILED"); if(matched!=templateId)return failed("CLOVA_TEMPLATE_MISMATCH"); JsonNode nodes=image.path("fields");if(!nodes.isArray())return failed("CLOVA_FIELDS_MISSING");
        Map<String,Object> values=new LinkedHashMap<>(),confidence=new LinkedHashMap<>();for(JsonNode node:nodes){String name=node.path("name").asText("").trim();if(!name.isEmpty()&&!values.containsKey(name)){values.put(name,node.path("inferText").asText(""));confidence.put(name,node.path("inferConfidence").isNumber()?node.path("inferConfidence").decimalValue():null);}}
        Map<String,Object> result=new LinkedHashMap<>();result.put("matchedTemplateId",matched);result.put("inferResult",infer);result.put("fields",values);result.put("confidence",confidence);result.put("standard",standard(values));return new OcrResult("CLOVA_TEMPLATE","SUCCEEDED",result,null);
    }catch(Exception e){return failed("CLOVA_RESPONSE_INVALID");}}
    private static Map<String,Object> standard(Map<String,Object> values){Map<String,Object> result=new LinkedHashMap<>();result.put("depositorName",text(values,"depositor_name"));result.put("agent",Map.of("name",text(values,"agent_name")));result.put("accounts",rows(values,"account_","_number","accountNo",3));result.put("delegators",delegators(values));result.put("reference",Map.of("depositorBirthDate",birthDate(text(values,"1_생년월일")),"agentBirthDate",birthDate(text(values,"2_생년월일")),"submittedDate",date(text(values,"제출일"))));return result;}
    private static List<Map<String,String>> rows(Map<String,Object> values,String prefix,String suffix,String key,int max){List<Map<String,String>> rows=new ArrayList<>();for(int i=1;i<=max;i++){String value=text(values,prefix+i+suffix);if(!value.isBlank())rows.add(Map.of(key,value));}return rows;}
    private static String text(Map<String,Object> v,String n){Object x=v.get(n);return x==null?"":String.valueOf(x).trim();}
    /** A row exists only when it names a delegator: blank rows of the form still carry a printed date label. */
    private static List<Map<String,String>> delegators(Map<String,Object> values){List<Map<String,String>> rows=new ArrayList<>();for(int i=1;i<=DELEGATOR_ROWS;i++){String name=text(values,"delegator_"+i+"_name");if(name.isBlank())continue;Map<String,String> row=new LinkedHashMap<>();row.put("name",name);String birthDate=birthDate(text(values,"delegator_"+i+"_birth6"));if(!birthDate.isBlank())row.put("birthDate",birthDate);rows.add(row);}return rows;}
    private static String date(String value){String digits=digits(value);return digits.length()==8&&realDate(digits)?digits:"";}
    /** The form asks for the first six resident-number digits, whose century is unknown, so those stay six digits. */
    private static String birthDate(String value){String digits=digits(value);if(digits.length()==6)return realDate("20"+digits)?digits:"";return date(digits);}
    private static String digits(String value){return value.replaceAll("[^0-9]","");}
    private static boolean realDate(String digits){try{LocalDate.parse(digits,DateTimeFormatter.BASIC_ISO_DATE);return true;}catch(DateTimeParseException e){return false;}}
    private static String required(String value,String label){if(value==null||value.isBlank())throw new IllegalStateException("C-02 CLOVA "+label+" is required.");return value.trim();}
    private static String format(String type,String name){if("application/pdf".equals(type))return "pdf";if("image/png".equals(type))return "png";if("image/jpeg".equals(type))return name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".jpeg")?"jpeg":"jpg";throw new IllegalArgumentException("Unsupported CLOVA OCR file format.");}
    private static OcrResult failed(String reason){return new OcrResult("CLOVA_TEMPLATE","FAILED_NEEDS_STAFF",Map.of(),reason);}
}
