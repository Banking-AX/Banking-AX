package com.bankingax.task.b01;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class B01ClovaOcrAdapterTest {
    private static final String SECRET = "test-secret-never-log";
    private static final String TEMPLATE = "73192";
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestSecret = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsV2MultipartWithSecretAndMapsRequiredTextFields() {
        respond(200, success(TEMPLATE, fields(
                field("날짜", "2026년 11월 03일"),
                field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26,500,000원"),
                field("기한의 이익 상실일", "2026.08.31"),
                field("변경 후 비용-원금-이자", "선택"),
                field("본인 서명", "홍길동"))));

        B01ClovaOcrAdapter adapter = new B01ClovaOcrAdapter(url("/infer"), SECRET,
                TEMPLATE + ",73193", Duration.ofSeconds(1), Duration.ofSeconds(2),
                new ObjectMapper(), null);
        B01OcrPort.OcrResult result = adapter.extract(request());

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.method()).isEqualTo("CLOVA_TEMPLATE");
        assertThat(result.fields().loans()).singleElement().satisfies(loan -> {
            assertThat(loan.accountNumber()).isEqualTo("MOCK-B01-T01");
            assertThat(loan.balance()).isEqualByComparingTo("26500000");
            assertThat(loan.accelerationDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        });
        assertThat(result.fields().applicationDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(result.fields().requestedRepaymentOrder()).isNull();
        assertThat(result.signatureChecks().debtorSignatureField()).isEqualTo("NOT_VERIFIED");
        assertThat(result.authenticityVerified()).isFalse();
        assertThat(result.manualVerificationRequired()).isTrue();
        assertThat(requestSecret.get()).isEqualTo(SECRET);
        assertThat(requestBody.get()).contains("name=\"message\"").contains("name=\"file\"")
                .contains("\"version\":\"V2\"").contains("\"templateIds\":[73192,73193]");
    }

    @Test
    void rejectsAnotherTemplateAndMissingOrMalformedRequiredFields() {
        respond(200, success("99", fields(field("계좌번호", "MOCK-B01-T01"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_TEMPLATE_MISMATCH");

        replaceResponse(200, success(TEMPLATE, fields(field("계좌번호", "MOCK-B01-T01"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_REQUIRED_FIELD_MISSING");

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "2026-11-03"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "금액미상"), field("기한의 이익 상실일", "2026-08-31"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_BALANCE_INVALID");

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "날짜미상"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "1000"), field("기한의 이익 상실일", "2026-08-31"))));
        B01OcrPort.OcrResult optionalInvalidDate = adapter(Duration.ofSeconds(2)).extract(request());
        assertThat(optionalInvalidDate.status()).isEqualTo("SUCCEEDED");
        assertThat(optionalInvalidDate.fields().applicationDate()).isNull();
    }

    @Test
    void acceptsWhitespaceAndSupportedDateSeparatorsWithoutGuessingDigits() {
        respond(200, success(TEMPLATE, fields(
                field("날짜", "2026. \\n 11. \\n 3."), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26,500,000"), field("기한의 이익 상실일", "2026/08/31"))));

        B01OcrPort.OcrResult separated = adapter(Duration.ofSeconds(2)).extract(request());
        assertThat(separated.status()).isEqualTo("SUCCEEDED");
        assertThat(separated.fields().applicationDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(separated.fields().loans()).singleElement()
                .extracting(B01OcrPort.LoanField::accelerationDate)
                .isEqualTo(LocalDate.of(2026, 8, 31));

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "2026년 / 11월 3일"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26,500,000원"), field("기한의 이익 상실일", "2026-08-31"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void keepsAccelerationDateRequiredAndTreatsApplicationDateAsOptional() {
        respond(200, success(TEMPLATE, fields(
                field("날짜", "2026-11-03"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26500000"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_ACCELERATION_DATE_MISSING");

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "2026-11-03"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26500000"), field("기한의 이익 상실일", "2026-O8-31"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_ACCELERATION_DATE_INVALID");

        replaceResponse(200, success(TEMPLATE, fields(
                field("계좌번호", "MOCK-B01-T01"), field("대출잔액", "26500000"),
                field("기한의 이익 상실일", "2026-08-31"))));
        B01OcrPort.OcrResult missingApplicationDate = adapter(Duration.ofSeconds(2)).extract(request());
        assertThat(missingApplicationDate.status()).isEqualTo("SUCCEEDED");
        assertThat(missingApplicationDate.fields().applicationDate()).isNull();

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "2026-13-03"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26500000"), field("기한의 이익 상실일", "2026-08-31"))));
        B01OcrPort.OcrResult invalidApplicationDate = adapter(Duration.ofSeconds(2)).extract(request());
        assertThat(invalidApplicationDate.status()).isEqualTo("SUCCEEDED");
        assertThat(invalidApplicationDate.fields().applicationDate()).isNull();

        replaceResponse(200, success(TEMPLATE, fields(
                field("날짜", "2026-11-03"), field("계좌번호", "MOCK-B01-T01"),
                field("대출잔액", "26500000"), field("기한의 이익 상실일", "2026-02-30"))));
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_ACCELERATION_DATE_INVALID");
    }

    @Test
    void mapsAuthenticationRejectionAndServiceFailureWithoutExposingResponseBody() {
        respond(401, "{\"message\":\"secret detail\"}");
        B01OcrPort.OcrResult unauthorized = adapter(Duration.ofSeconds(2)).extract(request());
        assertThat(unauthorized.failureReason()).isEqualTo("CLOVA_AUTHENTICATION_FAILED")
                .doesNotContain("secret detail");

        replaceResponse(503, "temporarily unavailable");
        assertThat(adapter(Duration.ofSeconds(2)).extract(request()).failureReason())
                .isEqualTo("CLOVA_UNAVAILABLE");
    }

    @Test
    void mapsTimeoutToManualReview() {
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            write(exchange, 200, success(TEMPLATE, fields()));
        });
        B01ClovaOcrAdapter adapter = new B01ClovaOcrAdapter(url("/slow"), SECRET, TEMPLATE,
                Duration.ofSeconds(1), Duration.ofMillis(50), new ObjectMapper(), null);
        assertThat(adapter.extract(request()).failureReason()).isEqualTo("CLOVA_TIMEOUT");
    }

    private B01ClovaOcrAdapter adapter(Duration readTimeout) {
        return new B01ClovaOcrAdapter(url("/infer"), SECRET, TEMPLATE, Duration.ofSeconds(1),
                readTimeout, new ObjectMapper(), null);
    }

    private B01OcrPort.ExtractRequest request() {
        return new B01OcrPort.ExtractRequest(1, "B01_REPAYMENT_ORDER_APPLICATION", "checksum",
                "application.pdf", "application/pdf", "%PDF-test".getBytes(StandardCharsets.UTF_8));
    }

    private void respond(int status, String body) {
        server.createContext("/infer", exchange -> {
            requestSecret.set(exchange.getRequestHeaders().getFirst("X-OCR-SECRET"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, status, body);
        });
    }

    private void replaceResponse(int status, String body) {
        server.removeContext("/infer");
        respond(status, body);
    }

    private static void write(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String field(String name, String text) {
        return "{\"name\":\"" + name + "\",\"inferText\":\"" + text + "\",\"type\":\"NORMAL\"}";
    }

    private static String fields(String... fields) { return String.join(",", fields); }

    private static String success(String template, String fields) {
        return "{\"version\":\"V2\",\"images\":[{\"inferResult\":\"SUCCESS\","
                + "\"matchedTemplate\":{\"id\":" + template + "},\"fields\":[" + fields + "]}]}";
    }
}
