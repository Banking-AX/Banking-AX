package com.bankingax.core.caseport.jpa;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.BankingAxApplication;
import com.bankingax.core.api.DomainException;
import com.bankingax.core.caseport.*;
import com.bankingax.core.workflow.TaskCode;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties={"app.case-port=jpa", "app.tool-gateway-token=test-token"})
class JpaCasePortMySqlIT {
  @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.36").withDatabaseName("banking_ax_it").withUsername("test").withPassword("test");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",MYSQL::getJdbcUrl); r.add("spring.datasource.username",MYSQL::getUsername); r.add("spring.datasource.password",MYSQL::getPassword);
  }
  @Autowired CasePort cases; @Autowired JdbcTemplate jdbc;
  private static long number(Number n){return n.longValue();}
  @BeforeEach void seed() {
    jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
    for(String table : new String[]{"case_revision","service_case","loan","work_item","workflow","decedent","user","branch"}) jdbc.update("delete from `"+table+"`");
    jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    jdbc.update("insert into branch(id,branch_code,name,address) values(1,'IT','IT','test')");
    jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values(1,'customer-1','x','CUSTOMER','customer')");
    jdbc.update("insert into workflow(id,customer_id,status) values(10,1,'RUNNING')");
    jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(101,10,'DEBT_ADJUSTMENT',1,'ACTIVE','c01-create-request')");
    jdbc.update("insert into work_item(id,workflow_id,task_code,sequence_no,status,request_id) values(102,10,'INHERITANCE_DEPOSIT',2,'PLANNED','c02-create-request')");
    jdbc.update("insert into loan(id,customer_id,loan_no,principal_amount,balance) values(501,1,'loan-501',100,100)");
    jdbc.update("insert into loan(id,customer_id,loan_no,principal_amount,balance) values(502,1,'loan-502',100,100)");
    jdbc.update("insert into decedent(id,name,death_date,deposit_total) values(701,'decedent','2020-01-01',0)");
    jdbc.update("insert into decedent(id,name,death_date,deposit_total) values(702,'another decedent','2020-01-01',0)");
  }
  @Test void persistsC01AndC02SnapshotsWithOwnershipRevisionAndCreateIdempotency() {
    var c01=cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of("reason","income loss"),null,false));
    var replay=cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of("reason","income loss"),null,false));
    assertEquals(c01.caseId(),replay.caseId()); assertEquals("income loss",replay.application().get("reason"));
    assertDuplicateCreate(()->cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of("reason","changed"),null,false)));
    assertDuplicateCreate(()->cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,502L,Map.of("reason","income loss"),null,false)));
    assertThrows(DomainException.class,()->cases.get(2,c01.caseId()));
    var changed=cases.revise(1,c01.caseId(),0,Map.of("reason","changed"),"CUSTOMER_UPDATE");
    assertEquals(1,changed.revision()); assertEquals("changed",cases.get(1,c01.caseId()).application().get("reason"));
    assertStoredVersion(changed);
    var checked=cases.recordCheckResult(c01.caseId(),changed.revision(),ReviewStatus.READY_FOR_STAFF);
    assertStoredVersion(checked);
    assertStoredVersion(cases.cancel(1,c01.caseId(),checked.version()));
    assertThrows(DomainException.class,()->cases.revise(1,c01.caseId(),0,Map.of(),"STALE"));
    assertEquals(2,number(jdbc.queryForObject("select count(*) from case_revision where case_id=?",Number.class,c01.caseId())));
    var c02=cases.create(new CreateCaseCommand(1,102,TaskCode.INHERITANCE_DEPOSIT,null,Map.of("c02",Map.of("heirs",1,"application","snapshot")),701L,true));
    assertEquals(701L,c02.decedentId()); assertTrue(c02.visitRequired()); assertEquals("snapshot",((Map<?,?>)cases.get(1,c02.caseId()).application().get("c02")).get("application"));
    assertDuplicateCreate(()->cases.create(new CreateCaseCommand(1,102,TaskCode.INHERITANCE_DEPOSIT,null,Map.of("c02",Map.of("heirs",2,"application","snapshot")),701L,true)));
    assertDuplicateCreate(()->cases.create(new CreateCaseCommand(1,102,TaskCode.INHERITANCE_DEPOSIT,null,Map.of("c02",Map.of("heirs",1,"application","snapshot")),702L,true)));
    assertDuplicateCreate(()->cases.create(new CreateCaseCommand(1,102,TaskCode.INHERITANCE_DEPOSIT,null,Map.of("c02",Map.of("heirs",1,"application","snapshot")),701L,false)));
  }
  @Test void dataIsReadableAfterANewSpringContextStarts() {
    var created=cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of("restart","persisted"),null,false));
    var submitted=cases.submit(1,created.caseId(),0,"submit-restart");
    assertStoredVersion(submitted);
    try(ConfigurableApplicationContext restarted=new SpringApplicationBuilder(BankingAxApplication.class).web(WebApplicationType.NONE).run(
        "--app.case-port=jpa", "--spring.datasource.url="+MYSQL.getJdbcUrl(), "--spring.datasource.username="+MYSQL.getUsername(), "--spring.datasource.password="+MYSQL.getPassword(), "--app.tool-gateway-token=test-token")) {
      CasePort afterRestart=restarted.getBean(CasePort.class);
      assertEquals("persisted",afterRestart.get(1,created.caseId()).application().get("restart"));
      assertEquals(submitted,afterRestart.submit(1,created.caseId(),0,"submit-restart"));
    }
  }
  @Test void persistsSubmitRequestIdAndRejectsDifferentRequestId() {
    var created=cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of(),null,false));
    var submitted=cases.submit(1,created.caseId(),0,"submit-one");
    assertStoredVersion(submitted);
    assertEquals(submitted,cases.submit(1,created.caseId(),0,"submit-one"));
    var error=assertThrows(DomainException.class,()->cases.submit(1,created.caseId(),0,"submit-two"));
    assertEquals("INVALID_STATE",error.code());
    assertEquals("submit-one",jdbc.queryForObject("select submit_request_id from service_case where id=?",String.class,created.caseId()));
  }
  @Test void concurrentSameSubmitRequestReturnsTheSinglePersistedResult() throws Exception {
    var created=cases.create(new CreateCaseCommand(1,101,TaskCode.DEBT_ADJUSTMENT,501L,Map.of(),null,false));
    ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
    try {
      Future<ServiceCaseView> first=pool.submit(()->{start.await(); return cases.submit(1,created.caseId(),0,"submit-concurrent");});
      Future<ServiceCaseView> second=pool.submit(()->{start.await(); return cases.submit(1,created.caseId(),0,"submit-concurrent");});
      start.countDown(); var firstResult=first.get(10,TimeUnit.SECONDS); var secondResult=second.get(10,TimeUnit.SECONDS); assertEquals(firstResult,secondResult); assertStoredVersion(firstResult);
    } finally { pool.shutdownNow(); }
  }
  private void assertDuplicateCreate(Executable action) {
    var error=assertThrows(DomainException.class,action); assertEquals("DUPLICATE_REQUEST_CONFLICT",error.code());
  }
  private void assertStoredVersion(ServiceCaseView view) {
    assertEquals(view.version(),number(jdbc.queryForObject("select version from service_case where id=?",Number.class,view.caseId())));
  }
}
