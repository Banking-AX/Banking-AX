package com.bankingax.task.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bankingax.core.auth.TaskBffPrincipal;
import com.bankingax.core.auth.TaskBffTestSecurityConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerAccountBffController.class)
@Import({TaskBffPrincipal.class, TaskBffTestSecurityConfiguration.class})
class CustomerAccountBffControllerTest {
    @Autowired MockMvc http;
    @MockBean NamedParameterJdbcTemplate jdbc;

    @Test
    void returnsOnlyJwtOwnedMaskedA01Accounts() throws Exception {
        when(jdbc.query(any(String.class), any(Map.class), any(RowMapper.class))).thenReturn(List.of(
                new CustomerAccountBffController.AccountOption(7, "***-****-1234", "Deposit", "ACTIVE", null, null)));
        http.perform(get("/api/v1/customer/accounts/a01?requestId=accounts-1")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].accountId").value(7))
                .andExpect(jsonPath("$.data[0].maskedAccountNo").value("***-****-1234"))
                .andExpect(jsonPath("$.data[0].accountNo").doesNotExist());
        verify(jdbc).query(any(String.class), eq(Map.of("customerId", 42L)), any(RowMapper.class));
    }

    @Test
    void returnsB02VersionButRejectsStaffAndAnonymousRequests() throws Exception {
        when(jdbc.query(any(String.class), any(Map.class), any(RowMapper.class))).thenReturn(List.of(
                new CustomerAccountBffController.AccountOption(8, "***-****-4321", "Housing savings", "ACTIVE", 3, new BigDecimal("2500000.00"))));
        http.perform(get("/api/v1/customer/accounts/b02?requestId=accounts-2")
                        .with(jwt().jwt(token -> token.subject("42").claim("role", "CUSTOMER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].version").value(3));
        http.perform(get("/api/v1/customer/accounts/a01?requestId=accounts-3")
                        .with(jwt().jwt(token -> token.subject("50").claim("role", "STAFF"))
                                .authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/customer/accounts/b02?requestId=accounts-4")).andExpect(status().isForbidden());
    }
}
