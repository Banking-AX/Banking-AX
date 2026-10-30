package com.bankingax.core.workflow.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = "app.case-port=jpa")
class TaskResultMigrationMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_task_result_it")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from task_result");
        jdbc.update("delete from loan");
        jdbc.update("delete from work_item");
        jdbc.update("delete from workflow");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(1,'task-result-customer','x','CUSTOMER','customer')");
        jdbc.update("insert into workflow(id,customer_id,status,version) values(1,1,'RUNNING',0)");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id,version) values"
                + "(1,1,'LOAN_INQUIRY',1,'ACTIVE','task-result-request-1',0),"
                + "(2,1,'LOAN_INQUIRY',2,'PLANNED','task-result-request-2',0)");
        jdbc.update("insert into loan(id,customer_id,loan_no,loan_name,principal_amount,balance,repayment_order) "
                + "values(1,1,'210-000-000001','test loan',1000000,900000,'INTEREST_FIRST')");
    }

    @Test
    void flywayCreatesExpectedColumnsAndConstraints() {
        Integer version = jdbc.queryForObject(
                "select max(cast(version as unsigned)) from flyway_schema_history where success=1", Integer.class);
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema=database() and table_name='task_result' order by ordinal_position",
                String.class);

        assertThat(version).isEqualTo(4);
        assertThat(columns).containsExactly("id", "work_item_id", "result_type", "decision_code",
                "target_loan_id", "rule_version", "basis_doc_id", "before_value", "after_value",
                "detail", "created_at");
        assertThat(jdbc.queryForList(
                "select delete_rule from information_schema.referential_constraints "
                        + "where constraint_schema=database() and table_name='task_result' order by constraint_name",
                String.class)).containsExactly("RESTRICT", "RESTRICT");

        jdbc.update("insert into task_result(work_item_id,result_type,target_loan_id,detail) "
                + "values(1,'LOAN_INQUIRY',1,json_object('count',3))");
        assertThat(jdbc.queryForObject("select count(*) from task_result", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select abs(timestampdiff(microsecond,created_at,utc_timestamp(6))) < 5000000 "
                        + "from task_result where work_item_id=1", Boolean.class)).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                "insert into task_result(work_item_id,result_type) values(1,'LOAN_INQUIRY')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into task_result(work_item_id,result_type) values(999,'LOAN_INQUIRY')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into task_result(work_item_id,result_type,target_loan_id) "
                        + "values(2,'LOAN_INQUIRY',999)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
