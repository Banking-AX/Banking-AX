package com.bankingax.task.b01;

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
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "app.b01.image-analysis-enabled", havingValue = "true")
final class B01FastApiImageAnalysisAdapter implements B01DocumentImageAnalysisPort {
    private static final Logger log = LoggerFactory.getLogger(B01FastApiImageAnalysisAdapter.class);
    private static final String PATH = "/api/v1/internal/document-analysis/b01";
    private static final Set<String> ALIGNMENTS = Set.of("ALIGNED", "ALIGNMENT_FAILED", "TEMPLATE_MISMATCH");
    private static final Set<String> BOX_STATES = Set.of("CHECKED", "UNCHECKED", "UNCERTAIN");
    private static final Set<String> ORDERS = Set.of("PRINCIPAL_FIRST", "INTEREST_FIRST", "NONE", "MULTIPLE", "UNCERTAIN");
    private static final Set<String> SIGNATURE_STATES = Set.of("TRACE_PRESENT", "TRACE_ABSENT", "UNCERTAIN");
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    B01FastApiImageAnalysisAdapter(
            @Value("${app.customer-agent-base-url:}") String baseUrl,
            @Value("${app.customer-agent-token:}") String token,
            @Value("${app.b01.image-analysis-timeout-seconds:10}") long timeoutSeconds,
            ObjectMapper json) {
        this(baseUrl, token, Duration.ofSeconds(timeoutSeconds), json, null);
    }

    B01FastApiImageAnalysisAdapter(String baseUrl, String token, Duration timeout,
                                   ObjectMapper json, HttpClient client) {
        String normalized = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        if (normalized.isBlank() || token == null || token.isBlank()
                || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("B-01 image analysis settings are required when enabled.");
        }
        try {
            endpoint = URI.create(normalized + PATH);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("B-01 image analysis URL is invalid.", exception);
        }
        this.token = token.strip();
        this.timeout = timeout;
        this.json = json;
        this.http = client != null ? client : HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public B01OcrPort.VisualChecks analyze(AnalysisRequest request) {
        try {
            String boundary = "banking-ax-image-" + UUID.randomUUID();
            HttpRequest outbound = HttpRequest.newBuilder(endpoint)
                    // Uvicorn's local HTTP server does not support Java HttpClient's h2c upgrade.
                    // Keep this trusted internal multipart boundary on HTTP/1.1.
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Customer-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, request)))
                    .build();
            HttpResponse<String> response = http.send(outbound,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("B-01 image analysis rejected request: status={}", response.statusCode());
                return unavailable("IMAGE_ANALYSIS_AUTH_FAILED");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("B-01 image analysis returned non-success: status={}, validation={}",
                        response.statusCode(), validationSummary(response.body()));
                return unavailable(response.statusCode() == 503
                        ? "IMAGE_ANALYSIS_UNAVAILABLE" : "IMAGE_ANALYSIS_FAILED");
            }
            return parse(response.body(), request.requestId());
        } catch (HttpTimeoutException exception) {
            log.warn("B-01 image analysis timed out: type={}", exception.getClass().getSimpleName());
            return unavailable("IMAGE_ANALYSIS_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("B-01 image analysis interrupted: type={}", exception.getClass().getSimpleName());
            return unavailable("IMAGE_ANALYSIS_INTERRUPTED");
        } catch (Exception exception) {
            Throwable cause = exception.getCause();
            log.warn("B-01 image analysis transport/response failure: type={}, causeType={}, message={}",
                    exception.getClass().getSimpleName(),
                    cause == null ? "none" : cause.getClass().getSimpleName(),
                    safeExceptionMessage(exception.getMessage()));
            return unavailable("IMAGE_ANALYSIS_UNAVAILABLE");
        }
    }

    private B01OcrPort.VisualChecks parse(String body, String requestId) throws Exception {
        JsonNode root = json.readTree(body);
        if (!requestId.equals(root.path("requestId").asText())
                || !"5-202-0557".equals(root.path("formCode").asText())) {
            return unavailable("IMAGE_ANALYSIS_INVALID_RESPONSE");
        }
        String alignment = required(root, "alignmentStatus");
        String first = required(root, "firstBox");
        String second = required(root, "secondBox");
        String selected = required(root, "selectedOrder");
        String signature = required(root, "debtorSignatureTrace");
        if (!ALIGNMENTS.contains(alignment) || !BOX_STATES.contains(first) || !BOX_STATES.contains(second)
                || !ORDERS.contains(selected) || !SIGNATURE_STATES.contains(signature)
                || !root.has("uncertain") || !root.path("uncertain").isBoolean()
                || !root.has("manualVerificationRequired")
                || !root.path("manualVerificationRequired").isBoolean()
                || !root.path("manualVerificationRequired").asBoolean())
            return unavailable("IMAGE_ANALYSIS_INVALID_RESPONSE");
        return new B01OcrPort.VisualChecks(alignment, first, second, selected, signature,
                root.path("uncertain").asBoolean(true), true, null);
    }

    private static String required(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Missing image analysis field");
        return value;
    }

    /** Logs FastAPI validation metadata only; request content and credentials never enter logs. */
    private String validationSummary(String body) {
        try {
            JsonNode detail = json.readTree(body).path("detail");
            if (!detail.isArray()) return "none";
            var entries = new ArrayList<String>();
            for (JsonNode item : detail) {
                String type = item.path("type").asText("unknown");
                JsonNode location = item.path("loc");
                var parts = new ArrayList<String>();
                if (location.isArray()) for (JsonNode part : location) parts.add(part.asText());
                entries.add(type + "@" + String.join(".", parts));
            }
            return entries.isEmpty() ? "none" : String.join(",", entries);
        } catch (Exception ignored) {
            return "unparseable";
        }
    }

    private static String safeExceptionMessage(String value) {
        if (value == null || value.isBlank()) return "none";
        // Java HTTP client transport messages contain protocol diagnostics, not request bodies or headers.
        return value.replaceAll("[\\r\\n]", " ").substring(0, Math.min(value.length(), 160));
    }

    private static byte[] multipart(String boundary, AnalysisRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        part(output, boundary, "requestId", null,
                request.requestId().getBytes(StandardCharsets.UTF_8), null);
        part(output, boundary, "file", request.contentType(), request.content(), safeName(request.fileName()));
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static void part(ByteArrayOutputStream output, String boundary, String name,
                             String contentType, byte[] content, String fileName) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (fileName == null ? "" : "; filename=\"" + fileName + "\"") + "\r\n"
                + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String safeName(String value) {
        String name = value == null ? "application.bin" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "application.bin" : name;
    }

    private static B01OcrPort.VisualChecks unavailable(String reason) {
        return new B01OcrPort.VisualChecks("ALIGNMENT_FAILED", "UNCERTAIN", "UNCERTAIN",
                "UNCERTAIN", "UNCERTAIN", true, true, reason);
    }
}
