package com.bankingax.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankingax.task.c02.OcrPersistence;
import com.bankingax.task.c02.MockOcrAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.target=10",
        "app.c02.ocr-provider=mock"
})
class DocumentExtractionScopeMigrationMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("banking_ax_document_extraction_scope_it")
            .withUsername("test")
            .withPassword("test")
            .withEnv("TZ", "UTC");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;

    @Test
    void migratesExistingC02RowsAndSupportsCaseLessB01Extractions() {
        seedV10DocumentGraph();
        jdbc.update("""
                INSERT INTO document_extraction
                  (id, document_file_id, case_id, revision, method, status, extracted)
                VALUES
                  (1000, 100, 700, 1, 'MOCK', 'SUCCEEDED', JSON_OBJECT('legacy', true))
                """);

        migrateToLatest();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='11' AND success=1
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('12', '13') AND success=1
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_type
                 WHERE code='B01_REPAYMENT_ORDER_APPLICATION'
                   AND source_kind='SHINHAN_OFFICIAL'
                   AND source_verified=TRUE
                   AND ocr_enabled=TRUE
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_type
                 WHERE code='ABSENT_HEIR_POWER_OF_ATTORNEY'
                   AND source_kind='SHINHAN_OFFICIAL'
                   AND source_doc_id='C02-02'
                   AND source_verified=TRUE
                   AND ocr_enabled=TRUE
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='document_extraction' AND column_name='case_id'
                """)).containsEntry("is_nullable", "YES");
        assertThat(jdbc.queryForMap("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='document_extraction' AND column_name='revision'
                """)).containsEntry("is_nullable", "YES");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE constraint_schema=DATABASE() AND table_name='document_extraction'
                   AND constraint_name='ck_extraction_case_revision_pair' AND constraint_type='CHECK'
                """, Integer.class)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(extracted, '$.legacy'))
                  FROM document_extraction WHERE id=1000 AND case_id=700 AND revision=1
                """, String.class)).isEqualTo("true");

        jdbc.update("""
                INSERT INTO document_extraction
                  (document_file_id, case_id, revision, method, status, extracted)
                VALUES
                  (200, NULL, NULL, 'MOCK', 'SUCCEEDED', JSON_OBJECT('taskCode', 'REPAYMENT_ORDER_CHANGE'))
                """);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_extraction
                 WHERE document_file_id=200 AND case_id IS NULL AND revision IS NULL
                """, Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO document_extraction
                  (document_file_id, case_id, revision, method, status)
                VALUES (201, 700, NULL, 'MOCK', 'SUCCEEDED')
                """))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO document_extraction
                  (document_file_id, case_id, revision, method, status)
                VALUES (202, NULL, 1, 'MOCK', 'SUCCEEDED')
                """))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO document_extraction
                  (document_file_id, case_id, revision, method, status)
                VALUES (999, NULL, NULL, 'MOCK', 'SUCCEEDED')
                """))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("UPDATE document_file SET is_latest=FALSE WHERE id=100");
        jdbc.update("""
                INSERT INTO document_file
                  (id, required_document_id, version, is_latest, original_name, extension,
                   size_bytes, storage_path, content_type, checksum, case_revision, uploaded_by)
                VALUES
                  (101, 800, 2, TRUE, 'c02-v2.pdf', 'pdf', 10,
                   '00000000-0000-0000-0000-000000000101.pdf', 'application/pdf',
                   REPEAT('b', 64), 1, 1)
                """);
        var ocrPersistence = new OcrPersistence(jdbc, new MockOcrAdapter(), objectMapper);
        ocrPersistence.extract(101, 700, 1, "SHINHAN_INHERITANCE_APPLICATION",
                "00000000-0000-0000-0000-000000000101.pdf");

        var snapshot = ocrPersistence.snapshot(700);
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0))
                .containsEntry("documentFileId", 101L)
                .containsEntry("method", "NONE")
                .containsEntry("status", "NOT_APPLICABLE");
    }

    private void migrateToLatest() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void seedV10DocumentGraph() {
        jdbc.update("""
                INSERT INTO `user`(id, login_id, password_hash, `role`, name)
                VALUES (1, 'document-customer', 'x', 'CUSTOMER', 'customer')
                """);
        jdbc.update("""
                INSERT INTO workflow(id, customer_id, status) VALUES (10, 1, 'RUNNING'), (20, 1, 'RUNNING')
                """);
        jdbc.update("""
                INSERT INTO work_item(id, workflow_id, task_code, sequence_no, status, request_id)
                VALUES
                  (100, 10, 'INHERITANCE_DEPOSIT', 1, 'ACTIVE', 'c02-document-work'),
                  (200, 20, 'REPAYMENT_ORDER_CHANGE', 1, 'ACTIVE', 'b01-document-work')
                """);
        jdbc.update("""
                INSERT INTO service_case
                  (id, work_item_id, customer_id, case_type, case_status, revision,
                   visit_required, application)
                VALUES
                  (700, 100, 1, 'INHERITANCE_DEPOSIT', 'DRAFT', 1, TRUE, JSON_OBJECT())
                """);
        jdbc.update("""
                INSERT INTO case_revision
                  (case_id, revision, application_snapshot, change_reason, created_by_user_id)
                VALUES (700, 1, JSON_OBJECT(), 'DOCUMENT_UPLOAD', 1)
                """);
        jdbc.update("""
                INSERT INTO document_type
                  (code, name, submission_kind, source_kind, source_doc_id,
                   source_verified, ocr_enabled)
                VALUES
                  ('SHINHAN_INHERITANCE_APPLICATION', 'C02 official form', 'UPLOAD',
                   'SHINHAN_OFFICIAL', 'C02-OFFICIAL', TRUE, TRUE),
                  ('B01_SCOPE_TEST_DOCUMENT', 'B01 scope migration fixture', 'UPLOAD',
                   'SHINHAN_OFFICIAL', 'B01-SCOPE-TEST', TRUE, TRUE)
                """);
        jdbc.update("""
                INSERT INTO required_document
                  (id, work_item_id, document_type_code, requirement, status)
                VALUES
                  (800, 100, 'SHINHAN_INHERITANCE_APPLICATION', 'REQUIRED', 'UPLOADED'),
                  (900, 200, 'B01_SCOPE_TEST_DOCUMENT', 'REQUIRED', 'UPLOADED')
                """);
        jdbc.update("""
                INSERT INTO document_file
                  (id, required_document_id, version, is_latest, original_name, extension,
                   size_bytes, storage_path, content_type, checksum, case_revision, uploaded_by)
                VALUES
                  (100, 800, 1, TRUE, 'c02.pdf', 'pdf', 10,
                   '00000000-0000-0000-0000-000000000100.pdf', 'application/pdf',
                   REPEAT('a', 64), 1, 1),
                  (200, 900, 1, TRUE, 'b01.pdf', 'pdf', 10,
                   '00000000-0000-0000-0000-000000000200.pdf', 'application/pdf',
                   REPEAT('c', 64), 0, 1),
                  (201, 900, 2, FALSE, 'b01-v2.pdf', 'pdf', 10,
                   '00000000-0000-0000-0000-000000000201.pdf', 'application/pdf',
                   REPEAT('d', 64), 0, 1),
                  (202, 900, 3, FALSE, 'b01-v3.pdf', 'pdf', 10,
                   '00000000-0000-0000-0000-000000000202.pdf', 'application/pdf',
                   REPEAT('e', 64), 0, 1)
                """);
    }
}
