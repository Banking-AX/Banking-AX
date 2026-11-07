package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises V5's staff outcome, customer notification, audit, and stale-revision boundaries. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "app.case-port=jpa", "app.c02.review-persistence-enabled=true",
    "app.c02.document-port=inmemory", "app.c02.document-provisioning-enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class C02StaffDecisionPersistenceMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("c02_decision_it").withUsername("test").withPassword("test");

    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
        MYSQL.start(); // PER_CLASS creates the instance, and so the context, before @Container would start it
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired C02StaffDecisionPersistence store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.update("delete from event_log"); jdbc.update("delete from notification"); jdbc.update("delete from staff_review");
        jdbc.update("delete from review_item"); jdbc.update("delete from agent_review_run"); jdbc.update("delete from case_revision");
        jdbc.update("delete from service_case"); jdbc.update("delete from work_item"); jdbc.update("delete from workflow");
        jdbc.update("delete from `user`"); jdbc.update("delete from branch");
        jdbc.update("insert into branch(id,branch_code,name,address) values(1,'B1','fixture','fixture')");
        jdbc.update("insert into `user`(id,login_id,password_hash,role,name) values(1,'customer','x','CUSTOMER','customer'),(2,'staff','x','STAFF','staff')");
        jdbc.update("insert into workflow(id,customer_id,status) values(10,1,'WAITING_CONTINUE')");
        jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(11,10,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','c02-it')");
        jdbc.update("insert into service_case(id,work_item_id,customer_id,case_type,case_status,review_status,revision,visit_required,application) values(12,11,1,'INHERITANCE_DEPOSIT','SUBMITTED','READY_FOR_STAFF',1,true,'{}')");
        jdbc.update("insert into case_revision(case_id,revision,application_snapshot,change_reason,created_by_user_id) values(12,1,'{}','TEST',1)");
    }

    @Test void persists_final_outcome_notification_and_idempotent_audit_replay() {
        var first = store.persist(2,12,1,"staff-request-1","PROCEED","ready for visit","REVIEW_PASSED");
        var replay = store.persist(2,12,1,"staff-request-1","PROCEED","ready for visit","REVIEW_PASSED");

        assertEquals(first, replay);
        assertEquals("REVIEW_PASSED", jdbc.queryForObject("select case_status from service_case where id=12",String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from staff_review where case_id=12 and result='PROCEED'",Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from notification where case_id=12",Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from event_log where request_id='staff-request-1'",Integer.class));
        assertEquals(1, store.notifications(1).size());
        assertThrows(DomainException.class, () -> store.persist(2,12,1,"another-request","PROCEED","ready for visit","REVIEW_PASSED"));
    }

    @Test void rejects_a_stale_case_revision_before_staff_result_is_saved() {
        assertThrows(DomainException.class, () -> store.persist(2,12,0,"stale-request","PROCEED","ready","REVIEW_PASSED"));
        assertEquals(0, jdbc.queryForObject("select count(*) from staff_review",Integer.class));
        assertEquals("SUBMITTED", jdbc.queryForObject("select case_status from service_case where id=12",String.class));
    }

    @Test void persisted_result_is_available_to_a_fresh_store_instance_after_restart() {
        store.persist(2,12,1,"restart-request","DOCUMENT_INSUFFICIENT","submit another document","REVIEW_REJECTED_DOCUMENT");

        var restarted = new C02StaffDecisionPersistence(jdbc);
        assertEquals("DOCUMENT_INSUFFICIENT", restarted.replay("restart-request").outcome());
        assertEquals("REVIEW_REJECTED_DOCUMENT", restarted.finalDecision(12).targetStatus());
        assertEquals(1, restarted.notifications(1).size());
    }
}
