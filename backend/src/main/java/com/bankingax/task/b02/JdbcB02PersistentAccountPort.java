package com.bankingax.task.b02;

import com.bankingax.core.api.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** MySQL implementation for the existing B-02 command contract; it is opt-in by configuration. */
@Component
@ConditionalOnProperty(name = "app.b02.account-port", havingValue = "jdbc")
class JdbcB02PersistentAccountPort implements B02PersistentAccountPort {
    private static final String EVENT_TYPE = "B02_HOUSING_SAVINGS_CLOSE";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String ruleVersion;

    JdbcB02PersistentAccountPort(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                 @Value("${app.b02.rule-version:1.7.1}") String ruleVersion) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.ruleVersion = ruleVersion;
    }

    @Override
    public B02Account find(long accountId) {
        var rows = jdbc.query("""
                SELECT id, customer_id, joined_at, subscription_won, status, version
                  FROM housing_savings_account WHERE id=:accountId
                """, Map.of("accountId", accountId), (rs, row) -> new B02Account(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getObject("joined_at", LocalDate.class),
                rs.getBoolean("subscription_won"), rs.getString("status"), rs.getInt("version")));
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "Housing savings account was not found.");
        return rows.get(0);
    }

    @Override
    public Optional<B02Service.R> replay(B02Service.C command) {
        var rows = jdbc.query("SELECT payload FROM event_log WHERE request_id=:requestId AND event_type=:eventType",
                Map.of("requestId", command.requestId(), "eventType", EVENT_TYPE), (rs, row) -> rs.getString(1));
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(readReceipt(rows.get(0), command));
    }

    /** Account close and durable receipt are intentionally in one actual Spring transaction. */
    @Override
    @Transactional
    public B02Service.R close(B02Service.C command) {
        B02Account account = locked(command.account());
        Optional<B02Service.R> previous = replay(command);
        if (previous.isPresent()) return previous.get();
        owned(account, command.customer());
        if (!"ACTIVE".equals(account.status()) || account.version() != command.version()) {
            throw new DomainException("STATE_CHANGED", "Account state changed.");
        }
        B02Service.P decision = B02Service.judge(account, command.waive(), command.taxed());
        if (decision.manual()) throw new DomainException("MANUAL_REVIEW_REQUIRED", "Branch consultation is required.");
        if ("DOCUMENT_REQUIRED".equals(decision.rate())) {
            throw new DomainException("REQUIRED_DOCUMENT_MISSING", "Tax certificate confirmation or waiver is required.");
        }

        int changed = jdbc.update("""
                UPDATE housing_savings_account
                   SET status='CLOSED', closed_at=:closedAt, applied_rate_type=:rate, version=version+1
                 WHERE id=:accountId AND status='ACTIVE' AND version=:version
                """, new MapSqlParameterSource()
                .addValue("closedAt", Timestamp.from(Instant.now()))
                .addValue("rate", decision.rate()).addValue("accountId", account.id()).addValue("version", account.version()));
        if (changed != 1) throw new DomainException("STATE_CHANGED", "Account state changed.");

        B02Service.R receipt = new B02Service.R(command.requestId(), command.customer(), command.account(),
                "CLOSED", decision.rate(), decision.rule(), command);
        jdbc.update("""
                INSERT INTO event_log(entity_type,entity_id,event_type,from_value,to_value,actor_type,actor_id,request_id,payload)
                VALUES('HOUSING_SAVINGS_ACCOUNT',:accountId,:eventType,'ACTIVE','CLOSED','CUSTOMER',:customerId,:requestId,:payload)
                """, new MapSqlParameterSource().addValue("accountId", account.id()).addValue("eventType", EVENT_TYPE)
                .addValue("customerId", command.customer()).addValue("requestId", command.requestId()).addValue("payload", receiptJson(receipt)));
        return receipt;
    }

    private B02Account locked(long accountId) {
        var rows = jdbc.query("""
                SELECT id, customer_id, joined_at, subscription_won, status, version
                  FROM housing_savings_account WHERE id=:accountId FOR UPDATE
                """, Map.of("accountId", accountId), (rs, row) -> new B02Account(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getObject("joined_at", LocalDate.class),
                rs.getBoolean("subscription_won"), rs.getString("status"), rs.getInt("version")));
        if (rows.isEmpty()) throw new DomainException("NOT_FOUND", "Housing savings account was not found.");
        return rows.get(0);
    }

    private void owned(B02Account account, long customerId) {
        if (account.customerId() != customerId) throw new DomainException("FORBIDDEN", "Customer ownership cannot be verified.");
    }

    private String receiptJson(B02Service.R receipt) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("requestId", receipt.requestId()); value.put("customer", receipt.customer()); value.put("account", receipt.account());
            value.put("status", receipt.status()); value.put("rate", receipt.rate()); value.put("rule", receipt.rule());
            value.put("version", receipt.command().version()); value.put("waive", receipt.command().waive());
            value.put("taxed", receipt.command().taxed()); value.put("bindingReference", receipt.command().binding());
            value.put("ruleVersion", ruleVersion);
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize B-02 execution receipt", ex); }
    }

    private B02Service.R readReceipt(String payload, B02Service.C supplied) {
        try {
            JsonNode value = objectMapper.readTree(payload);
            B02Service.C stored = new B02Service.C(value.path("requestId").asText(), value.path("customer").asLong(),
                    value.path("account").asLong(), value.path("version").asInt(), value.path("waive").asBoolean(),
                    value.has("taxed") && !value.get("taxed").isNull() ? value.get("taxed").asBoolean() : null,
                    value.path("bindingReference").asText(), supplied.workItemId());
            B02Service.R receipt = new B02Service.R(stored.requestId(), stored.customer(), stored.account(),
                    value.path("status").asText(), value.path("rate").asText(), value.path("rule").asText(), stored);
            if (!receipt.same(supplied)) throw new DomainException("DUPLICATE_REQUEST_CONFLICT", "requestId was reused.");
            return receipt;
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot read B-02 execution receipt", ex); }
    }
}
