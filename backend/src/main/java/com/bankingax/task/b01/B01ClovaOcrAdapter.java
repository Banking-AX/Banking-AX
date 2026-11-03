package com.bankingax.task.b01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CLOVA Template OCR adapter. It extracts text only; signatures, checkboxes and authenticity stay unverified. */
@Component
@ConditionalOnProperty(name = "app.b01.ocr-provider", havingValue = "clova")
final class B01ClovaOcrAdapter implements B01OcrPort {
    private static final Logger log = LoggerFactory.getLogger(B01ClovaOcrAdapter.class);
    private static final String DOCUMENT_TYPE = "B01_REPAYMENT_ORDER_APPLICATION";
    private static final String FIELD_DATE = "날짜";
    private static final String FIELD_ACCOUNT = "계좌번호";
    private static final String FIELD_BALANCE = "대출잔액";
    private static final String FIELD_ACCELERATION_DATE = "기한의 이익 상실일";
    private static final Pattern DIGIT_GROUP = Pattern.compile("[0-9]+");

    private final URI invokeUrl;
    private final String secret;
    private final List<Long> templateIds;
    private final Duration readTimeout;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    B01ClovaOcrAdapter(
            @Value("${app.b01.clova.invoke-url:}") String invokeUrl,
            @Value("${app.b01.clova.secret:}") String secret,
            @Value("${app.b01.clova.template-id:}") String templateId,
            @Value("${app.b01.clova.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${app.b01.clova.read-timeout-seconds:15}") long readTimeoutSeconds,
            ObjectMapper json) {
        this(invokeUrl, secret, templateId, Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(readTimeoutSeconds), json, null);
    }

    B01ClovaOcrAdapter(String invokeUrl, String secret, String templateId, Duration connectTimeout,
                       Duration readTimeout, ObjectMapper json, HttpClient client) {
        if (invokeUrl == null || invokeUrl.isBlank() || secret == null || secret.isBlank()
                || templateId == null || templateId.isBlank()) {
            throw new IllegalStateException("CLOVA OCR settings are required when the B-01 OCR provider is clova.");
        }
        try {
            this.invokeUrl = URI.create(invokeUrl.trim());
            this.templateIds = Arrays.stream(templateId.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::parseLong)
                    .distinct()
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("CLOVA OCR invoke URL or template ID is invalid.", exception);
        }
        if (!this.invokeUrl.isAbsolute() || this.templateIds.isEmpty()
                || this.templateIds.stream().anyMatch(id -> id <= 0) || connectTimeout.isNegative()
                || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("CLOVA OCR URL, template ID, and timeouts must be valid.");
        }
        this.secret = secret;
        this.readTimeout = readTimeout;
        this.json = json;
        this.http = client != null ? client : HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public String method() { return "CLOVA_TEMPLATE"; }

    @Override
    public OcrResult extract(ExtractRequest request) {
        try {
            String boundary = "banking-ax-" + UUID.randomUUID();
            byte[] body = multipart(boundary, request);
            HttpRequest httpRequest = HttpRequest.newBuilder(invokeUrl)
                    .timeout(readTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-OCR-SECRET", secret)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403)
                return failed("CLOVA_AUTHENTICATION_FAILED");
            if (response.statusCode() >= 400 && response.statusCode() < 500)
                return failed("CLOVA_REQUEST_REJECTED");
            if (response.statusCode() >= 500) return failed("CLOVA_UNAVAILABLE");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return failed("CLOVA_UNEXPECTED_RESPONSE");
            return parse(response.body());
        } catch (HttpTimeoutException exception) {
            return failed("CLOVA_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed("CLOVA_INTERRUPTED");
        } catch (Exception exception) {
            return failed("CLOVA_UNAVAILABLE");
        }
    }

    private byte[] multipart(String boundary, ExtractRequest request) throws Exception {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("format", format(request.contentType(), request.fileName()));
        image.put("name", "b01-application");
        image.put("templateIds", templateIds);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", "V2");
        message.put("requestId", UUID.randomUUID().toString());
        message.put("timestamp", System.currentTimeMillis());
        message.put("lang", "ko");
        message.put("images", List.of(image));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        part(output, boundary, "message", "application/json; charset=UTF-8",
                json.writeValueAsBytes(message), null);
        part(output, boundary, "file", request.contentType(), request.content(), safeFileName(request.fileName()));
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void part(ByteArrayOutputStream output, String boundary, String name, String contentType,
                             byte[] content, String fileName) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (fileName == null ? "" : "; filename=\"" + fileName + "\"")
                + "\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private OcrResult parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode images = root.path("images");
            if (!images.isArray() || images.size() != 1) return failed("CLOVA_IMAGE_RESULT_INVALID");
            JsonNode image = images.get(0);
            String inferResult = image.path("inferResult").asText("");
            long matchedTemplateId = image.path("matchedTemplate").path("id").asLong(-1);
            log.info("CLOVA B-01 OCR response: inferResult={}, matchedTemplateId={}",
                    inferResult, matchedTemplateId);
            if (!"SUCCESS".equals(inferResult)) return failed("CLOVA_INFERENCE_FAILED");
            if (!templateIds.contains(matchedTemplateId))
                return failed("CLOVA_TEMPLATE_MISMATCH");

            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode values = image.path("fields");
            if (!values.isArray()) return failed("CLOVA_FIELDS_MISSING");
            for (JsonNode field : values) {
                String name = field.path("name").asText("").trim();
                String text = field.path("inferText").asText("").trim();
                if (!name.isEmpty() && !fields.containsKey(name)) fields.put(name, text);
            }
            String account = required(fields, FIELD_ACCOUNT).replaceAll("\\s+", "");
            BigDecimal balance = balance(required(fields, FIELD_BALANCE));
            LocalDate accelerationDate = dateField(fields, FIELD_ACCELERATION_DATE,
                    "accelerationDate", "CLOVA_ACCELERATION_DATE_MISSING",
                    "CLOVA_ACCELERATION_DATE_INVALID");
            LocalDate applicationDate = optionalApplicationDate(fields.get(FIELD_DATE));
            if (account.isBlank()) return failed("CLOVA_REQUIRED_FIELD_MISSING");

            return new OcrResult(DOCUMENT_TYPE, DOCUMENT_TYPE, "CLOVA_TEMPLATE", "SUCCEEDED",
                    new Fields(List.of(new LoanField(account, balance, accelerationDate)), null, applicationDate),
                    unverifiedSignatures(), false, true, null);
        } catch (MissingFieldException exception) {
            return failed("CLOVA_REQUIRED_FIELD_MISSING");
        } catch (DateFieldException exception) {
            return failed(exception.reason);
        } catch (NumberFormatException exception) {
            return failed("CLOVA_BALANCE_INVALID");
        } catch (Exception exception) {
            return failed("CLOVA_RESPONSE_INVALID");
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) throw new MissingFieldException();
        return value.trim();
    }

    private static BigDecimal balance(String text) {
        String normalized = text.replace(",", "").replaceAll("\\s+", "").replace("원", "");
        if (!normalized.matches("[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
        return new BigDecimal(normalized);
    }

    private static LocalDate dateField(Map<String, String> fields, String fieldName, String diagnosticName,
                                       String missingReason, String invalidReason) {
        String text = fields.get(fieldName);
        if (text == null || text.isBlank()) {
            log.debug("CLOVA B-01 date diagnostic: field={}, state=missing", diagnosticName);
            throw new DateFieldException(missingReason);
        }
        try {
            return date(text);
        } catch (DateTimeException | NumberFormatException exception) {
            log.debug("CLOVA B-01 date diagnostic: field={}, state=invalid, length={}, digitGroupLengths={}, separatorKinds={}",
                    diagnosticName, text.length(), digitGroupLengths(text), separatorKinds(text));
            throw new DateFieldException(invalidReason);
        }
    }

    private static LocalDate optionalApplicationDate(String text) {
        if (text == null || text.isBlank()) {
            log.info("CLOVA B-01 optional date diagnostic: field=applicationDate, state=missing");
            return null;
        }
        try {
            return date(text);
        } catch (DateTimeException | NumberFormatException exception) {
            log.info("CLOVA B-01 optional date diagnostic: field=applicationDate, state=invalid, "
                            + "length={}, digitGroupCount={}, digitGroupLengths={}, separatorKinds={}",
                    text.length(), digitGroupLengths(text).size(), digitGroupLengths(text), separatorKinds(text));
            return null;
        }
    }

    private static LocalDate date(String text) {
        String separators = text.replaceAll("[0-9]", "");
        if (!separators.matches("[\\s년월일./-]*")) throw new DateTimeException("Unsupported date character") { };

        List<String> groups = new ArrayList<>();
        Matcher matcher = DIGIT_GROUP.matcher(text);
        while (matcher.find()) groups.add(matcher.group());
        if (groups.size() != 3 || groups.get(0).length() != 4
                || groups.get(1).length() > 2 || groups.get(2).length() > 2) {
            throw new DateTimeException("Date must contain year, month, and day") { };
        }
        return LocalDate.of(Integer.parseInt(groups.get(0)), Integer.parseInt(groups.get(1)),
                Integer.parseInt(groups.get(2)));
    }

    private static List<Integer> digitGroupLengths(String text) {
        List<Integer> lengths = new ArrayList<>();
        Matcher matcher = DIGIT_GROUP.matcher(text);
        while (matcher.find()) lengths.add(matcher.group().length());
        return lengths;
    }

    private static Set<String> separatorKinds(String text) {
        Set<String> kinds = new LinkedHashSet<>();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value >= '0' && value <= '9') continue;
            if (Character.isWhitespace(value)) kinds.add("WHITESPACE");
            else if (value == '.') kinds.add("DOT");
            else if (value == '/') kinds.add("SLASH");
            else if (value == '-') kinds.add("HYPHEN");
            else if (value == '년' || value == '월' || value == '일') kinds.add("KOREAN_DATE_MARKER");
            else kinds.add("OTHER");
        }
        return kinds;
    }

    private OcrResult failed(String reason) {
        return new OcrResult(DOCUMENT_TYPE, null, "CLOVA_TEMPLATE", "FAILED_NEEDS_STAFF",
                new Fields(List.of(), null, null), unverifiedSignatures(), false, true, reason);
    }

    private static SignatureChecks unverifiedSignatures() {
        return new SignatureChecks("NOT_VERIFIED", "NOT_VERIFIED");
    }

    private static String format(String contentType, String fileName) {
        if ("application/pdf".equals(contentType)) return "pdf";
        if ("image/png".equals(contentType)) return "png";
        if ("image/jpeg".equals(contentType)) return fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".jpeg") ? "jpeg" : "jpg";
        throw new IllegalArgumentException("Unsupported CLOVA OCR file format.");
    }

    private static String safeFileName(String fileName) {
        String format = format(fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? "application/pdf" : fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".png")
                ? "image/png" : "image/jpeg", fileName);
        return "b01-application." + format;
    }

    private static final class MissingFieldException extends RuntimeException { }
    private static final class DateFieldException extends RuntimeException {
        private final String reason;
        private DateFieldException(String reason) { this.reason = reason; }
    }
}
