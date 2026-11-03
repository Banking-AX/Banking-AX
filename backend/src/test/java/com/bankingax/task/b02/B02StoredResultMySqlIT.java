package com.bankingax.task.b02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.case-port=jpa")
class B02StoredResultMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_b02_result_it").withUsername("test").withPassword("test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired B02WorkflowService workflow;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM task_result WHERE work_item_id=9901");
        jdbc.update("DELETE FROM work_item WHERE id=9901");
        jdbc.update("DELETE FROM workflow WHERE id=9900");
        jdbc.update("DELETE FROM `user` WHERE id=9900");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(9900,'b02-result-it','x','CUSTOMER','result test')");
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES(9900,9900,'COMPLETED')");
        jdbc.update("INSERT INTO work_item(id,workflow_id,task_code,sequence_no,status,request_id) VALUES(9901,9900,'HOUSING_SAVINGS_CLOSE',1,'DONE','b02-result-work')");
        jdbc.update("INSERT INTO task_result(work_item_id,result_type,decision_code,rule_version,before_value,after_value,detail) VALUES(9901,'HOUSING_SAVINGS_CLOSE','B2-R1','1.7.1',CAST('{\"status\":\"ACTIVE\"}' AS JSON),CAST('{\"accountId\":9911,\"status\":\"CLOSED\",\"rate\":\"2.5\",\"version\":1}' AS JSON),CAST('{\"requestId\":\"b02-result-request\",\"result\":\"CLOSED\"}' AS JSON))");
    }

    @Test void returns_persisted_receipt_only_to_owning_customer() {
        B02WorkflowService.StoredResult result = workflow.result(9900, 9901);
        assertEquals("DONE", result.status());
        assertEquals("B2-R1", result.decisionCode());
        assertEquals(9911, result.afterValue().path("accountId").asLong());
        assertEquals("CLOSED", result.afterValue().path("status").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_result WHERE work_item_id=9901", Integer.class));
        assertThrows(DomainException.class, () -> workflow.result(9902, 9901));
    }

    @Test void refuses_to_return_a_result_for_an_active_or_non_b02_work_item() {
        jdbc.update("UPDATE work_item SET status='ACTIVE' WHERE id=9901");
        assertEquals("B02_RESULT_NOT_READY", assertThrows(DomainException.class,
                () -> workflow.result(9900, 9901)).code());
        jdbc.update("UPDATE work_item SET status='DONE',task_code='TRANSACTION_SEARCH' WHERE id=9901");
        assertEquals("WORK_ITEM_NOT_AVAILABLE", assertThrows(DomainException.class,
                () -> workflow.result(9900, 9901)).code());
    }
}
