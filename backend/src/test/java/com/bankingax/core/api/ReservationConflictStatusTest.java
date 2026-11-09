package com.bankingax.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Booking contention is a state conflict, not a malformed request. */
class ReservationConflictStatusTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private HttpStatus statusOf(String code) {
        return HttpStatus.valueOf(handler.handleDomain(new DomainException(code, "message", true)).getStatusCode().value());
    }

    @Test void bookingContentionIsAConflict() {
        assertThat(statusOf("RESERVATION_CONFLICT")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf("RESERVATION_ALREADY_EXISTS")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf("RESERVATION_SLOT_UNAVAILABLE")).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void otherDomainErrorsKeepTheirStatus() {
        assertThat(statusOf("VALIDATION_ERROR")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf("FORBIDDEN")).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
