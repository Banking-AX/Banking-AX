package com.bankingax.task.c02;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import com.bankingax.core.tool.ActorRole;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(C02StaffBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
@TestPropertySource(properties = "app.tool-gateway-token=server-only-token")
class C02StaffBffControllerTest {
    @Autowired MockMvc http;
    @MockBean C02Controller internal;
    @MockBean C02ReviewerAccess reviewers;

    @Test
    void staffWithoutTheReviewGrantReachNoCasePath() throws Exception {
        org.mockito.Mockito.doThrow(new com.bankingax.core.api.DomainException("FORBIDDEN", "no grant")).when(reviewers).require(202L);
        var staff = jwt().jwt(token -> token.subject("202").claim("role", "EMPLOYEE")).authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
        String revision = "{\"revision\":1,\"requestId\":\"r\"}";

        http.perform(get("/api/v1/staff/c02/55/staff-case").param("requestId", "r").with(staff)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/staff/c02/55/ocr").param("revision", "1").param("requestId", "r").with(staff)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/staff/c02/55/staff-review").param("requestId", "r").with(staff)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/staff/c02/55/documents/9/content").with(staff)).andExpect(status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/staff/c02/55/staff-precheck")
                .contentType("application/json").content(revision).with(staff)).andExpect(status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/staff/c02/55/staff-decision")
                .contentType("application/json").content("{\"revision\":1,\"requestId\":\"r\",\"outcome\":\"PROCEED\",\"customerMessage\":\"m\"}").with(staff)).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(internal);
    }

    @Test
    void staffJwtCanReadStaffCaseButCustomerJwtIsRejected() throws Exception {
        when(internal.staffCase(eq(55L), eq(ActorRole.STAFF), eq("server-only-token"), eq("staff-1")))
                .thenReturn(ApiEnvelope.success("staff-1", "INHERITANCE_DEPOSIT", null, 55L, null, null));

        http.perform(get("/api/v1/staff/c02/55/staff-case").param("requestId", "staff-1")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "EMPLOYEE"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk());
        verify(internal).staffCase(55L, ActorRole.STAFF, "server-only-token", "staff-1");

        http.perform(get("/api/v1/staff/c02/55/staff-case").param("requestId", "staff-2")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffOcrRequiresStaffJwtAndForwardsRequestedRevision() throws Exception {
        when(internal.staffOcr(eq(55L), eq(ActorRole.STAFF), eq("server-only-token"), eq(3), eq("ocr-1")))
                .thenReturn(ApiEnvelope.success("ocr-1", "INHERITANCE_DEPOSIT", null, 55L, 3, null));
        http.perform(get("/api/v1/staff/c02/55/ocr").param("revision", "3").param("requestId", "ocr-1")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "EMPLOYEE"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk());
        verify(internal).staffOcr(55L, ActorRole.STAFF, "server-only-token", 3, "ocr-1");
        http.perform(get("/api/v1/staff/c02/55/ocr").param("revision", "3").param("requestId", "ocr-2")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffDocumentReturnsTheStoredBytesAndRejectsCustomerJwt() throws Exception {
        byte[] original = "%PDF-1.4\nstaff can inspect this uploaded fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(internal.download(eq(55L), eq(9L), eq(101L), eq(ActorRole.STAFF), eq("server-only-token"), eq(null)))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                        .body(new InputStreamResource(new ByteArrayInputStream(original))));

        http.perform(get("/api/v1/staff/c02/55/documents/9/content")
                        .with(jwt().jwt(token -> token.subject("101").claim("role", "EMPLOYEE"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(original));
        verify(internal).download(55L, 9L, 101L, ActorRole.STAFF, "server-only-token", null);

        http.perform(get("/api/v1/staff/c02/55/documents/9/content")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }
}
