package com.bankingax.core.auth;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;

final class SecurityErrorWriter {
    private SecurityErrorWriter() { }

    static void write(HttpServletResponse response, ObjectMapper json, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = UUID.randomUUID().toString();
        json.writeValue(response.getOutputStream(), new ApiEnvelope<>(null, null, null, null, null,
                null, new ApiError(code, message, false, traceId)));
    }
}
