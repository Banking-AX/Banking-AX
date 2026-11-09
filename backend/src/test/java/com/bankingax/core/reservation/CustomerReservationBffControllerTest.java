package com.bankingax.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bankingax.core.auth.TaskBffPrincipal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The staff member is always Spring's choice: a customer request cannot name one and skip the workload order. */
class CustomerReservationBffControllerTest {
    private final VisitReservationController internal = mock(VisitReservationController.class);
    private final VisitReservationService service = mock(VisitReservationService.class);
    private final TaskBffPrincipal principals = mock(TaskBffPrincipal.class);
    private final JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
    private final CustomerReservationBffController controller = new CustomerReservationBffController(internal, service, principals, "gateway");
    private final VisitReservationController.Create naming = new VisitReservationController.Create("r1", 7L, 1L, 102L, OffsetDateTime.now().plusDays(3));

    @Test void aBookingCannotNameTheStaffMember() {
        when(principals.customer(token)).thenReturn(3L);
        controller.create(token, naming);
        ArgumentCaptor<VisitReservationController.Create> sent = ArgumentCaptor.forClass(VisitReservationController.Create.class);
        verify(internal).create(eq(3L), any(), eq("gateway"), sent.capture());
        assertThat(sent.getValue().staffId()).isNull();
        assertThat(sent.getValue().caseId()).isEqualTo(7L);
        assertThat(sent.getValue().branchId()).isEqualTo(1L);
    }

    @Test void aChangeCannotNameTheStaffMember() {
        when(principals.customer(token)).thenReturn(3L);
        controller.change(token, 5L, naming);
        ArgumentCaptor<VisitReservationController.Create> sent = ArgumentCaptor.forClass(VisitReservationController.Create.class);
        verify(service).change(anyLong(), eq(5L), sent.capture());
        assertThat(sent.getValue().staffId()).isNull();
        assertThat(sent.getValue().requestId()).isEqualTo("r1");
    }
}
