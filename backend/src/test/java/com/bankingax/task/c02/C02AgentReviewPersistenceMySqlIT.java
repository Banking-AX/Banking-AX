package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import java.util.List;
import java.util.Map;
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

/** MySQL-only test: V1-V3 run first, then canonical review DDL is applied as test fixture. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"app.c02.review-persistence-enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class C02AgentReviewPersistenceMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36").withDatabaseName("c02_review_it").withUsername("test").withPassword("test");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) { r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername); r.add("spring.datasource.password", MYSQL::getPassword); }
    @Autowired C02AgentReviewPersistence store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc.execute("DROP TABLE IF EXISTS review_item"); jdbc.execute("DROP TABLE IF EXISTS agent_review_run");
        jdbc.execute("CREATE TABLE agent_review_run (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, case_id BIGINT UNSIGNED NOT NULL, revision INT UNSIGNED NOT NULL, check_type VARCHAR(30) NOT NULL DEFAULT 'STAFF_PRECHECK', status VARCHAR(20) NOT NULL, attempt_count INT UNSIGNED NOT NULL DEFAULT 0, model_version VARCHAR(90) NULL, prompt_version VARCHAR(60) NULL, rule_version VARCHAR(40) NULL, failure_reason VARCHAR(255) NULL, report JSON NULL, started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL, UNIQUE KEY uk_reviewrun_revision(case_id,revision,check_type), CONSTRAINT fk_reviewrun_revision FOREIGN KEY(case_id,revision) REFERENCES case_revision(case_id,revision)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE review_item (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, case_id BIGINT UNSIGNED NOT NULL, revision INT UNSIGNED NOT NULL, agent_review_run_id BIGINT UNSIGNED NULL, source VARCHAR(30) NOT NULL, category VARCHAR(40) NOT NULL, result VARCHAR(36) NOT NULL, rule_id VARCHAR(70) NULL, rule_version VARCHAR(40) NULL, basis_doc_id VARCHAR(60) NULL, document_file_id BIGINT UNSIGNED NULL, heir_id BIGINT UNSIGNED NULL, message TEXT NULL, evidence JSON NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), CONSTRAINT fk_reviewitem_revision FOREIGN KEY(case_id,revision) REFERENCES case_revision(case_id,revision), CONSTRAINT fk_reviewitem_run FOREIGN KEY(agent_review_run_id) REFERENCES agent_review_run(id), CONSTRAINT fk_reviewitem_file FOREIGN KEY(document_file_id) REFERENCES document_file(id), CONSTRAINT fk_reviewitem_heir FOREIGN KEY(heir_id) REFERENCES heir(id)) ENGINE=InnoDB");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"document_file","required_document","heir","case_revision","service_case","work_item","workflow","document_type","user"}) jdbc.update("DELETE FROM `" + table + "`");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,role,name) VALUES(1,'c02-review','x','CUSTOMER','synthetic')");
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES(1,1,'RUNNING')");
        jdbc.update("INSERT INTO work_item(id,workflow_id,task_code,sequence_no,status,request_id) VALUES(2,1,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','review-it')");
        jdbc.update("INSERT INTO service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application) VALUES(3,2,1,'INHERITANCE_DEPOSIT','SUBMITTED',1,true,'{}')");
        jdbc.update("INSERT INTO case_revision(case_id,revision,application_snapshot,change_reason) VALUES(3,1,'{}','TEST')");
        jdbc.update("INSERT INTO heir(id,case_id,person_ref,name,relationship,visit_status) VALUES(4,3,'h1','synthetic','child','ATTENDING')");
        jdbc.update("INSERT INTO document_type(code,name,submission_kind,per_person) VALUES('HEIR_IDENTITY_CARD','id','UPLOAD',true)");
        jdbc.update("INSERT INTO required_document(id,work_item_id,document_type_code,heir_id,requirement,status) VALUES(5,2,'HEIR_IDENTITY_CARD',4,'REQUIRED','NEEDS_SUPPLEMENT')");
        jdbc.update("INSERT INTO document_file(id,required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,case_revision,uploaded_by) VALUES(6,5,2,true,'fixture.pdf','pdf',10,'fixture-key',1,1)");
    }

    @Test void persists_validated_items_replays_and_requeries() {
        var response = response(6L, 4L);
        var first = store.persist("review-1", 3, 1, response);
        var replay = store.persist("review-1", 3, 1, response);
        assertEquals(first.runId(), replay.runId()); assertEquals(2, first.items().size());
        assertEquals(first, store.find(3, 1));
        assertEquals(1, jdbc.queryForObject("select count(*) from agent_review_run", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from review_item", Integer.class));
        assertThrows(DomainException.class, () -> store.persist("review-other", 3, 1, response));
    }

    @Test void rejects_foreign_or_stale_document_before_a_run_is_written() {
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES(10,1,'RUNNING')");
        jdbc.update("INSERT INTO work_item(id,workflow_id,task_code,sequence_no,status,request_id) VALUES(11,10,'INHERITANCE_DEPOSIT',1,'HANDED_OFF','other')");
        jdbc.update("INSERT INTO service_case(id,work_item_id,customer_id,case_type,case_status,revision,visit_required,application) VALUES(12,11,1,'INHERITANCE_DEPOSIT','SUBMITTED',1,true,'{}')");
        jdbc.update("INSERT INTO case_revision(case_id,revision,application_snapshot,change_reason) VALUES(12,1,'{}','TEST')");
        jdbc.update("INSERT INTO heir(id,case_id,person_ref,name,relationship,visit_status) VALUES(13,12,'h2','other','child','ATTENDING')");
        assertThrows(DomainException.class, () -> store.persist("bad", 3, 1, response(6L, 13L)));
        assertEquals(0, jdbc.queryForObject("select count(*) from agent_review_run", Integer.class));
        assertThrows(DomainException.class, () -> store.persist("stale", 3, 0, response(6L, 4L)));
    }

    private static StaffAgentPort.StaffAgentResponse response(Long fileId, Long heirId) {
        Map<String,Object> missing = Map.of("source","SPRING_RULE","category","DOCUMENT_MISSING","result","NEEDS_STAFF_REVIEW","basisDocId","DECEDENT_BASIC_CERTIFICATE","message","missing","evidence",Map.of("scope","COMMON"));
        Map<String,Object> supplement = Map.of("source","SPRING_RULE","category","DOCUMENT_NEEDS_SUPPLEMENT","result","NEEDS_STAFF_REVIEW","basisDocId","HEIR_IDENTITY_CARD","documentFileId",fileId,"heirId",heirId,"message","supplement","evidence",Map.of("scope","HEIR","personRef","h1","documentVersion",2));
        return new StaffAgentPort.StaffAgentResponse("SUCCEEDED","WAITING_CUSTOMER",List.of(missing,supplement),Map.of());
    }
}
