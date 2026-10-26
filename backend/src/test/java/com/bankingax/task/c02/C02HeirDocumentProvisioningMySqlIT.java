package com.bankingax.task.c02;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"app.c02.document-port=jpa", "app.c02.document-provisioning-enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class C02HeirDocumentProvisioningMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("c02_provision_it").withUsername("test").withPassword("test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired C02HeirDocumentProvisioningService provisioning;
    @Autowired DocumentPort documents;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"document_file", "required_document", "heir", "delegation_receipt", "case_revision", "service_case", "work_item", "workflow", "document_type", "decedent", "user", "branch"})
            jdbc.update("DELETE FROM `" + table + "`");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO branch(id,branch_code,name,address) VALUES(1,'IT','IT','test')");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(1,'c02-customer','x','CUSTOMER','synthetic')");
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES(10,1,'RUNNING')");
        jdbc.update("INSERT INTO work_item(id,workflow_id,task_code,sequence_no,status,request_id) VALUES(100,10,'INHERITANCE_DEPOSIT',1,'ACTIVE','c02-provision')");
        jdbc.update("INSERT INTO decedent(id,name,death_date,deposit_total) VALUES(50,'decedent','2020-01-01',0)");
        jdbc.update("INSERT INTO delegation_receipt(receipt_no,decedent_id,delegator_name,agent_name,is_valid) VALUES('receipt-1',50,'receipt-heir','attending',true)");
        jdbc.update("INSERT INTO service_case(id,work_item_id,customer_id,case_type,case_status,decedent_id,visit_required,application) VALUES(200,100,1,'INHERITANCE_DEPOSIT','DRAFT',50,true,'{}')");
        for (String code : documentCodes()) {
            boolean perPerson = !code.startsWith("DECEDENT_") && !code.equals("PASSBOOK_AND_SEAL");
            String kind = code.equals("DELEGATION_RECEIPT_NO") ? "INPUT_VALUE"
                : code.equals("PASSBOOK_AND_SEAL") || code.equals("HEIR_SEAL_OR_SIGNATURE") ? "VISIT_ONLY" : "UPLOAD";
            jdbc.update("INSERT INTO document_type(code,name,submission_kind,per_person) VALUES(?,?,?,?)", code, code, kind, perPerson);
        }
    }

    @Test void persists_heirs_common_and_per_person_slots_and_links_files() {
        var command = new C02HeirDocumentProvisioningService.ProvisionCommand(200, 100, intake());
        var created = provisioning.provision(command);
        assertEquals(2, created.heirCount());
        assertEquals(8, created.requiredDocumentCount());
        assertEquals(3, count("SELECT COUNT(*) FROM required_document WHERE heir_id IS NULL"));
        assertEquals(5, count("SELECT COUNT(*) FROM required_document WHERE heir_id IS NOT NULL"));

        var common = documents.register(registration("DECEDENT_BASIC_CERTIFICATE:COMMON", 0, "00000000-0000-0000-0000-000000000101.pdf"));
        var first = documents.register(registration("HEIR_IDENTITY_CARD:h1", 0, "00000000-0000-0000-0000-000000000102.pdf"));
        var second = documents.register(registration("HEIR_IDENTITY_CARD:h1", 1, "00000000-0000-0000-0000-000000000103.pdf"));
        assertEquals("DECEDENT_BASIC_CERTIFICATE:COMMON", common.documentSlotKey());
        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals("UPLOADED", jdbc.queryForObject("""
            SELECT rd.status FROM required_document rd JOIN document_file df ON df.required_document_id=rd.id
            WHERE df.id=?
            """, String.class, second.documentFileId()));

        var retried = provisioning.provision(command);
        assertEquals(created, retried);
        assertEquals(8, count("SELECT COUNT(*) FROM required_document"));
        assertEquals(2, count("SELECT COUNT(*) FROM document_file WHERE required_document_id=(SELECT id FROM required_document WHERE document_type_code='HEIR_IDENTITY_CARD' AND heir_id=(SELECT id FROM heir WHERE person_ref='h1'))"));
    }

    @Test void rejects_missing_document_type_and_rolls_back_heirs_and_slots() {
        jdbc.update("DELETE FROM document_type WHERE code='HEIR_IDENTITY_CARD'");
        DomainException error = assertThrows(DomainException.class,
            () -> provisioning.provision(new C02HeirDocumentProvisioningService.ProvisionCommand(200, 100, intake())));
        assertEquals("DOCUMENT_TYPE_UNAVAILABLE", error.code());
        assertEquals(0, count("SELECT COUNT(*) FROM heir"));
        assertEquals(0, count("SELECT COUNT(*) FROM required_document"));
    }

    @Test void rejects_the_three_generic_demo_codes_without_losing_slot_identity() {
        jdbc.update("DELETE FROM document_type");
        jdbc.update("INSERT INTO document_type(code,name,submission_kind,per_person) VALUES('C02_FAMILY','family','UPLOAD',false)");
        jdbc.update("INSERT INTO document_type(code,name,submission_kind,per_person) VALUES('C02_DELEGATION','delegation','UPLOAD',true)");
        jdbc.update("INSERT INTO document_type(code,name,submission_kind,per_person) VALUES('C02_ID','id','UPLOAD',true)");
        DomainException error = assertThrows(DomainException.class,
            () -> provisioning.provision(new C02HeirDocumentProvisioningService.ProvisionCommand(200, 100, intake())));
        assertEquals("DOCUMENT_TYPE_UNAVAILABLE", error.code());
        assertEquals(0, count("SELECT COUNT(*) FROM heir"));
        assertEquals(0, count("SELECT COUNT(*) FROM required_document"));
    }

    @Test void persists_every_current_contract_slot_without_a_unique_collision() {
        var complete = new C02IntakeService.Intake(
            new C02IntakeService.DecedentInput("decedent", null, LocalDate.of(2020, 1, 1), 0), List.of(
                new C02IntakeService.HeirInput("h1", "attending", LocalDate.of(1980, 1, 1), "child", true, true,
                    C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE, null),
                new C02IntakeService.HeirInput("h2", "absent-document", LocalDate.of(1970, 1, 1), "child", false, false,
                    C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT, null),
                new C02IntakeService.HeirInput("h3", "receipt-heir", LocalDate.of(1960, 1, 1), "child", false, false,
                    C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.RECEIPT, "receipt-1")));

        var created = provisioning.provision(new C02HeirDocumentProvisioningService.ProvisionCommand(200, 100, complete));

        assertEquals(3, created.heirCount());
        assertEquals(10, created.requiredDocumentCount());
        assertEquals(10, count("SELECT COUNT(*) FROM required_document"));
        assertEquals(10, count("SELECT COUNT(DISTINCT CONCAT(document_type_code, ':', scope_heir_key)) FROM required_document"));
    }

    @Test void rejects_a_case_and_work_item_pair_that_is_not_persisted_together() {
        DomainException error = assertThrows(DomainException.class,
            () -> provisioning.provision(new C02HeirDocumentProvisioningService.ProvisionCommand(200, 999, intake())));
        assertEquals("CASE_WORKITEM_MISMATCH", error.code());
        assertEquals(0, count("SELECT COUNT(*) FROM heir"));
    }

    @Test void applies_the_c02_demo_seed_replacement_fragment_with_its_existing_file_link() throws Exception {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[]{"document_file", "required_document", "heir", "delegation_receipt", "service_case", "work_item", "workflow", "document_type", "decedent", "user", "branch"})
            jdbc.update("DELETE FROM `" + table + "`");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO branch(id,branch_code,name,address) VALUES(1,'DEMO','Demo','test')");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(1,'customer-demo','x','CUSTOMER','synthetic'),(101,'staff-demo','x','STAFF','synthetic')");
        jdbc.update("INSERT INTO workflow(id,customer_id,status) VALUES(3,1,'COMPLETED')");
        jdbc.update("INSERT INTO decedent(id,name,death_date,deposit_total) VALUES(1,'decedent','2020-01-01',0)");
        jdbc.update("INSERT INTO delegation_receipt(receipt_no,decedent_id,delegator_name,agent_name,is_valid) VALUES('MOCK-DEL-001',1,'김하나','김민수',true)");

        Path patch = Path.of("..", "docs", "banking_ax_demo_seed_c02_document_contract_patch.sql").toAbsolutePath();
        ScriptUtils.executeSqlScript(jdbc.getDataSource().getConnection(), new EncodedResource(new FileSystemResource(patch), StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO document_file(id,required_document_id,version,is_latest,original_name,extension,size_bytes,storage_path,uploaded_by) VALUES(3,5,1,true,'c02_family_demo.jpg','jpg',19610,'/mock-fixtures/c02_family_demo.jpg',1)");

        assertEquals(10, count("SELECT COUNT(*) FROM document_type"));
        assertEquals(5, count("SELECT COUNT(*) FROM required_document WHERE work_item_id=6"));
        assertEquals("INHERITANCE_DEPOSIT", jdbc.queryForObject("SELECT case_type FROM service_case WHERE id=2", String.class));
        assertEquals("DECEDENT_DETAILED_FAMILY_RELATION", jdbc.queryForObject("""
            SELECT rd.document_type_code FROM document_file df JOIN required_document rd ON rd.id=df.required_document_id WHERE df.id=3
            """, String.class));
    }

    private C02IntakeService.Intake intake() {
        return new C02IntakeService.Intake(new C02IntakeService.DecedentInput("decedent", null, LocalDate.of(2020, 1, 1), 0), List.of(
            new C02IntakeService.HeirInput("h1", "attending", LocalDate.of(1980, 1, 1), "child", true, false,
                C02IntakeService.VisitStatus.ATTENDING, C02IntakeService.DelegationMethod.NONE, null),
            new C02IntakeService.HeirInput("h2", "absent", LocalDate.of(1970, 1, 1), "child", false, false,
                C02IntakeService.VisitStatus.ABSENT, C02IntakeService.DelegationMethod.DOCUMENT, null)));
    }

    private DocumentPort.DocumentRegistration registration(String slot, int revision, String key) {
        return new DocumentPort.DocumentRegistration(200, revision, slot, "fixture.pdf", "application/pdf", 12, key,
            C02IntakeService.DocumentStatus.UPLOADED, slot.substring(0, slot.indexOf(':')), "a".repeat(64), Instant.now(), 1L);
    }

    private static List<String> documentCodes() {
        return List.of("DECEDENT_DETAILED_FAMILY_RELATION", "DECEDENT_BASIC_CERTIFICATE", "PASSBOOK_AND_SEAL",
            "DECEDENT_REMOVED_FAMILY_REGISTER", "HEIR_IDENTITY_CARD", "HEIR_SEAL_OR_SIGNATURE",
            "ABSENT_HEIR_ID_COPY", "ABSENT_HEIR_SEAL_CERTIFICATE", "ABSENT_HEIR_POWER_OF_ATTORNEY", "DELEGATION_RECEIPT_NO");
    }

    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
}
