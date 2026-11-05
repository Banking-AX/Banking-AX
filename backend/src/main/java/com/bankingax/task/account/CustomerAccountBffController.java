package com.bankingax.task.account;

import com.bankingax.core.api.ApiEnvelope;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.auth.TaskBffPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only account options scoped exclusively to the authenticated customer. */
@RestController
@RequestMapping("/api/v1/customer/accounts")
@com.bankingax.core.persistence.ConditionalOnDataSource
public class CustomerAccountBffController {
    private final NamedParameterJdbcTemplate jdbc;
    private final TaskBffPrincipal principals;

    public CustomerAccountBffController(NamedParameterJdbcTemplate jdbc, TaskBffPrincipal principals) {
        this.jdbc = jdbc;
        this.principals = principals;
    }

    @GetMapping("/a01")
    public ApiEnvelope<List<AccountOption>> a01(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        requireRequestId(requestId);
        var accounts = jdbc.query("""
                SELECT id, account_no, account_name, is_active
                FROM deposit_account WHERE customer_id=:customerId AND is_active=TRUE ORDER BY id
                """, Map.of("customerId", customerId), (rs, ignored) -> new AccountOption(
                rs.getLong("id"), mask(rs.getString("account_no")), rs.getString("account_name"),
                "ACTIVE", null, null));
        return ApiEnvelope.success(requestId, "TRANSACTION_SEARCH", null, null, null, accounts);
    }

    @GetMapping("/b02")
    public ApiEnvelope<List<AccountOption>> b02(JwtAuthenticationToken authentication, @RequestParam String requestId) {
        long customerId = principals.customer(authentication);
        requireRequestId(requestId);
        var accounts = jdbc.query("""
                SELECT id, account_no, paid_principal, status, version
                FROM housing_savings_account WHERE customer_id=:customerId ORDER BY id
                """, Map.of("customerId", customerId), (rs, ignored) -> new AccountOption(
                rs.getLong("id"), mask(rs.getString("account_no")), "Housing savings", rs.getString("status"),
                rs.getInt("version"), rs.getBigDecimal("paid_principal")));
        return ApiEnvelope.success(requestId, "HOUSING_SAVINGS_CLOSE", null, null, null, accounts);
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) throw new DomainException("INVALID_REQUEST", "requestId is required.");
    }

    private static String mask(String accountNo) {
        String digits = accountNo == null ? "" : accountNo.replaceAll("\\D", "");
        return digits.length() < 4 ? "***" : "***-****-" + digits.substring(digits.length() - 4);
    }

    public record AccountOption(long accountId, String maskedAccountNo, String productName, String status,
                                Integer version, BigDecimal balance) { }
}
