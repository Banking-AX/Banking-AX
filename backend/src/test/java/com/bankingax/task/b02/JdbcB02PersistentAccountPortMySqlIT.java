package com.bankingax.task.b02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.verification.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MySQL-only validation. The two financial tables are test DDL until T1 assigns an operating Flyway version. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.b02.account-port=jdbc", "app.tool-gateway-token=test-token"})
class JdbcB02PersistentAccountPortMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_b02_it").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcB02PersistentAccountPort port;
    private TestVerification verification;
    private B02Service service;

    @BeforeEach void seed() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS housing_savings_account (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL, account_no VARCHAR(40) NOT NULL, joined_at DATE NOT NULL, paid_principal DECIMAL(18,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', subscription_won BOOLEAN NOT NULL DEFAULT FALSE, converted BOOLEAN NOT NULL DEFAULT FALSE, applied_rate_type VARCHAR(30) NULL, tax_mock_data JSON NULL, closed_at DATETIME(6) NULL, version INT NOT NULL DEFAULT 0, UNIQUE KEY uk_housing_account_no (account_no), KEY idx_housing_customer (customer_id), CONSTRAINT fk_housing_customer FOREIGN KEY (customer_id) REFERENCES `user`(id), CONSTRAINT ck_housing_status CHECK (status IN ('ACTIVE','CLOSED'))) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS event_log (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, entity_type VARCHAR(50) NOT NULL, entity_id BIGINT UNSIGNED NOT NULL, event_type VARCHAR(100) NOT NULL, from_value VARCHAR(100) NULL, to_value VARCHAR(100) NULL, actor_type VARCHAR(20) NOT NULL, actor_id BIGINT UNSIGNED NULL, request_id VARCHAR(100) NOT NULL, payload JSON NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), UNIQUE KEY uk_event_operation (request_id), KEY idx_event_entity (entity_type,entity_id,created_at)) ENGINE=InnoDB");
        jdbc.update("DELETE FROM event_log"); jdbc.update("DELETE FROM housing_savings_account");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(1,'b02-customer-1','x','CUSTOMER','customer one') ON DUPLICATE KEY UPDATE name=VALUES(name)");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(2,'b02-customer-2','x','CUSTOMER','customer two') ON DUPLICATE KEY UPDATE name=VALUES(name)");
        jdbc.update("INSERT INTO housing_savings_account(id,customer_id,account_no,joined_at,status,subscription_won,version) VALUES(3001,1,'3101234000001','2023-01-01','ACTIVE',false,0),(3002,2,'3101234000002','2023-01-01','ACTIVE',false,0)");
        verification = new TestVerification(); service = new B02Service(verification, id -> { }, port);
    }

    @Test void closes_owned_account_and_replays_durable_identical_request() {
        B02Service.C command = command("b02-it-one", 1, 3001, 0, true, false);
        B02Service.R first = service.execute(command);
        B02Service.R replay = service.execute(command);
        assertEquals(first, replay); assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM housing_savings_account WHERE id=3001", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_log WHERE request_id='b02-it-one'", Integer.class));
        assertThrows(DomainException.class, () -> service.execute(new B02Service.C("b02-it-one",1,3001,0,false,false,"other")));
    }

    @Test void a_fresh_service_instance_recovers_the_durable_request_receipt() {
        B02Service.C command = command("b02-restart", 1, 3001, 0, true, false);
        B02Service.R executed = service.execute(command);
        B02Service afterRestart = new B02Service(new TestVerification(), id -> { }, port);
        assertEquals(executed, afterRestart.execute(command));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_log WHERE request_id='b02-restart'", Integer.class));
    }

    @Test void event_log_failure_rolls_back_the_account_close() {
        // A foreign event owns this request id. It is intentionally not a B-02 receipt, so replay cannot hide the DB UNIQUE failure.
        jdbc.update("INSERT INTO event_log(entity_type,entity_id,event_type,actor_type,request_id,payload) VALUES('TEST',1,'OTHER','SYSTEM','b02-rollback','{}')");
        assertThrows(DataAccessException.class, () -> service.execute(command("b02-rollback", 1, 3001, 0, true, false)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM housing_savings_account WHERE id=3001", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event_log WHERE event_type='B02_HOUSING_SAVINGS_CLOSE'", Integer.class));
    }

    @Test void rejects_foreign_missing_and_non_executable_rules_without_mutation() {
        assertThrows(DomainException.class, () -> service.prepare(1, 3002, true, false));
        assertThrows(DomainException.class, () -> service.prepare(1, 9999, true, false));
        assertThrows(DomainException.class, () -> service.execute(command("b02-manual", 1, 3001, 0, false, true)));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM housing_savings_account WHERE id=3001", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event_log", Integer.class));
    }

    @Test void database_lock_and_unique_receipt_allow_only_one_close_for_concurrent_request_ids() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?>[] results = new Future<?>[20];
            for (int i = 0; i < results.length; i++) { final int n = i; results[i] = pool.submit(() -> {
                start.await(); try { service.execute(command("b02-parallel-" + n, 1, 3001, 0, true, false)); } catch (DomainException expected) { assertEquals("STATE_CHANGED", expected.code()); } return null;
            }); }
            start.countDown(); for (Future<?> result : results) result.get();
        } finally { pool.shutdownNow(); }
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM housing_savings_account WHERE id=3001", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_log", Integer.class));
    }

    private B02Service.C command(String requestId, long customer, long account, int version, boolean waive, Boolean taxed) {
        String binding = verification.binding(customer, account, version);
        return new B02Service.C(requestId, customer, account, version, waive, taxed, binding);
    }

    private static final class TestVerification implements VerificationPort {
        private final Map<String, VerificationBinding> bindings = new ConcurrentHashMap<>();
        String binding(long customer, long account, int version) { String id = "binding-" + UUID.randomUUID(); bindings.put(id, new VerificationBinding(id, customer, account, VerificationAction.B02_EXECUTE, version, Instant.now().plusSeconds(60))); return id; }
        @Override public VerificationIssue issue(VerificationIssueCommand command) { throw new UnsupportedOperationException(); }
        @Override public VerificationBinding verify(VerificationVerifyCommand command) { throw new UnsupportedOperationException(); }
        @Override public void consume(VerificationConsumeCommand command) { VerificationBinding binding = bindings.remove(command.bindingId()); if (binding == null || binding.customerId()!=command.customerId() || binding.workItemId()!=command.workItemId() || binding.action()!=command.action() || binding.requestVersion()!=command.requestVersion()) throw new DomainException("VERIFICATION_BINDING_INVALID", "Verification binding cannot be used for this request."); }
    }
}
