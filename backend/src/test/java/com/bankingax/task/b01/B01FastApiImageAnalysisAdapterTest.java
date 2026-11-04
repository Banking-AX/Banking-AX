package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class B01FastApiImageAnalysisAdapterTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsMultipartFileAndInternalTokenAndParsesSuccessfulResult() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (Server server = server(200, """
                {"requestId":"analysis-1","formCode":"5-202-0557","alignmentStatus":"ALIGNED",
                 "firstBox":"CHECKED","secondBox":"UNCHECKED","selectedOrder":"PRINCIPAL_FIRST",
                 "debtorSignatureTrace":"TRACE_PRESENT","uncertain":false,
                 "manualVerificationRequired":true}
                """, 0, token, contentType, requestBody)) {
            var result = adapter(server, Duration.ofSeconds(2)).analyze(request("analysis-1"));
            assertThat(token.get()).isEqualTo("test-internal-token");
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
            assertThat(requestBody.get()).contains("name=\"requestId\"").contains("analysis-1")
                    .contains("name=\"file\"").contains("%PDF-");
            assertThat(result.alignmentStatus()).isEqualTo("ALIGNED");
            assertThat(result.selectedOrder()).isEqualTo("PRINCIPAL_FIRST");
            assertThat(result.debtorSignatureTrace()).isEqualTo("TRACE_PRESENT");
            assertThat(result.uncertain()).isFalse();
            assertThat(result.manualVerificationRequired()).isTrue();
        }
    }

    @Test
    void mapsAuthenticationAndServerFailuresWithoutExposingBodies() throws Exception {
        assertFailure(401, "sensitive auth body", "IMAGE_ANALYSIS_AUTH_FAILED");
        assertFailure(503, "sensitive unavailable body", "IMAGE_ANALYSIS_UNAVAILABLE");
        assertFailure(500, "sensitive error body", "IMAGE_ANALYSIS_FAILED");
    }

    @Test
    void failsClosedOnMismatchedRequestAndTimeout() throws Exception {
        try (Server server = server(200, """
                {"requestId":"different","formCode":"5-202-0557","alignmentStatus":"ALIGNED",
                 "firstBox":"CHECKED","secondBox":"UNCHECKED","selectedOrder":"PRINCIPAL_FIRST",
                 "debtorSignatureTrace":"TRACE_PRESENT","uncertain":false,
                 "manualVerificationRequired":true}
                """, 0, null, null, null)) {
            assertThat(adapter(server, Duration.ofSeconds(2)).analyze(request("analysis-1")).failureReason())
                    .isEqualTo("IMAGE_ANALYSIS_INVALID_RESPONSE");
        }
        try (Server server = server(200, "{}", 300, null, null, null)) {
            assertThat(adapter(server, Duration.ofMillis(50)).analyze(request("analysis-1")).failureReason())
                    .isEqualTo("IMAGE_ANALYSIS_TIMEOUT");
        }
    }

    private void assertFailure(int status, String body, String expected) throws Exception {
        try (Server server = server(status, body, 0, null, null, null)) {
            var result = adapter(server, Duration.ofSeconds(2)).analyze(request("analysis-1"));
            assertThat(result.failureReason()).isEqualTo(expected);
            assertThat(result.uncertain()).isTrue();
            assertThat(result.manualVerificationRequired()).isTrue();
        }
    }

    private B01FastApiImageAnalysisAdapter adapter(Server server, Duration timeout) {
        return new B01FastApiImageAnalysisAdapter("http://127.0.0.1:" + server.port(),
                "test-internal-token", timeout, json,
                HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    private static B01DocumentImageAnalysisPort.AnalysisRequest request(String requestId) {
        return new B01DocumentImageAnalysisPort.AnalysisRequest(requestId, "application.pdf",
                "application/pdf", "%PDF-synthetic".getBytes(StandardCharsets.UTF_8));
    }

    private static Server server(int status, String response, long delayMillis,
                                 AtomicReference<String> token, AtomicReference<String> contentType,
                                 AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/document-analysis/b01", exchange -> {
            if (token != null) token.set(exchange.getRequestHeaders().getFirst("X-Customer-Agent-Token"));
            if (contentType != null) contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (requestBody != null) requestBody.set(new String(body, StandardCharsets.ISO_8859_1));
            if (delayMillis > 0) try { Thread.sleep(delayMillis); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException ignored) {
                // The timeout test closes the client connection before the stub responds.
            } finally { exchange.close(); }
        });
        server.start();
        return new Server(server);
    }

    private record Server(HttpServer delegate) implements AutoCloseable {
        int port() { return delegate.getAddress().getPort(); }
        @Override public void close() { delegate.stop(0); }
    }
}
