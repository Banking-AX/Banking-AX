package com.bankingax.task.a01;

import static org.junit.jupiter.api.Assertions.*;

import com.bankingax.core.api.DomainException;
import com.bankingax.core.tool.ActorRole;
import com.bankingax.core.tool.ToolActor;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/** Exercises the JDBC adapter against MySQL 8; A-01 tables are test-only until T1 issues their Flyway migration. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={"app.a01.transaction-query-port=jdbc", "app.tool-gateway-token=test-token"})
class JdbcTransactionQueryPortMySqlIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.36").withDatabaseName("banking_ax_a01_it").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl); registry.add("spring.datasource.username",MYSQL::getUsername); registry.add("spring.datasource.password",MYSQL::getPassword);
    }
    @Autowired TransactionSearchService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS deposit_account (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT UNSIGNED NOT NULL, account_no VARCHAR(40) NOT NULL, account_name VARCHAR(120) NOT NULL, balance DECIMAL(18,2) NOT NULL DEFAULT 0, is_active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uk_deposit_account_no (account_no), KEY idx_deposit_customer (customer_id), CONSTRAINT fk_deposit_customer FOREIGN KEY (customer_id) REFERENCES `user`(id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_transaction (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, account_id BIGINT UNSIGNED NOT NULL, tx_at DATETIME(6) NOT NULL, direction VARCHAR(10) NOT NULL, amount DECIMAL(18,2) NOT NULL, counterparty VARCHAR(120) NULL, memo VARCHAR(255) NULL, KEY idx_transaction_search (account_id, tx_at, id), CONSTRAINT fk_transaction_account FOREIGN KEY (account_id) REFERENCES deposit_account(id), CONSTRAINT ck_transaction_direction CHECK (direction IN ('IN','OUT')), CONSTRAINT ck_transaction_amount CHECK (amount >= 0)) ENGINE=InnoDB");
        jdbc.update("DELETE FROM account_transaction"); jdbc.update("DELETE FROM deposit_account");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(1,'a01-customer-1','x','CUSTOMER','customer one') ON DUPLICATE KEY UPDATE name=VALUES(name)");
        jdbc.update("INSERT INTO `user`(id,login_id,password_hash,`role`,name) VALUES(2,'a01-customer-2','x','CUSTOMER','customer two') ON DUPLICATE KEY UPDATE name=VALUES(name)");
        jdbc.update("INSERT INTO deposit_account(id,customer_id,account_no,account_name,balance,is_active) VALUES(1001,1,'1101234000001','Synthetic checking',0,true),(2001,2,'1101234000002','Other checking',0,true)");
        insert(10,1001,"2026-10-01 00:00:00.000000","IN","100.00","Edge payer","opening");
        insert(11,1001,"2026-10-31 23:59:59.999999","OUT","200.00","Rent office","rent");
        insert(12,1001,"2026-10-20 09:00:00.000000","IN","1500.00","AX payroll","monthly pay");
        insert(13,1001,"2026-10-20 09:00:00.000000","OUT","300.00","AX mart","groceries");
        insert(14,1001,"2026-10-20 09:00:00.000000","OUT","400.00","AX mart","household");
        insert(20,2001,"2026-10-20 09:00:00.000000","IN","1.00","Other customer","isolation");
    }

    @Test void queries_my_sql_with_inclusive_dates_filters_stable_sort_and_pages() {
        var recent=search(1,1001,TransactionDirection.OUT,new BigDecimal("250"),new BigDecimal("350"),"mart",TransactionSortOrder.RECENT,0,20);
        assertEquals(1,recent.totalElements()); assertEquals(13,recent.transactions().get(0).transactionId());
        var full=search(1,1001,null,null,null,null,TransactionSortOrder.RECENT,0,2);
        assertEquals(5,full.totalElements()); assertEquals(11,full.transactions().get(0).transactionId(),"end-date final microsecond is included"); assertEquals(14,full.transactions().get(1).transactionId(),"same timestamps use id DESC");
        var secondPage=search(1,1001,null,null,null,null,TransactionSortOrder.RECENT,1,2);
        assertEquals(13,secondPage.transactions().get(0).transactionId()); assertEquals(12,secondPage.transactions().get(1).transactionId());
        var oldest=search(1,1001,null,null,null,null,TransactionSortOrder.OLDEST,0,20);
        assertEquals(10,oldest.transactions().get(0).transactionId()); assertEquals(12,oldest.transactions().get(1).transactionId(),"same timestamps use id ASC");
    }

    @Test void rejects_foreign_missing_and_invalid_requests_and_returns_empty_results() {
        assertThrows(DomainException.class,()->search(1,2001,null,null,null,null,TransactionSortOrder.RECENT,0,20));
        assertThrows(DomainException.class,()->search(1,9999,null,null,null,null,TransactionSortOrder.RECENT,0,20));
        assertThrows(DomainException.class,()->service.search(new ToolActor(1,ActorRole.CUSTOMER),new TransactionSearchRequest("bad",1L,1,1001,LocalDate.of(2026,11,1),LocalDate.of(2026,10,1),null,0,20,null,null,null,null)));
        assertThrows(DomainException.class,()->search(1,1001,null,new BigDecimal("10"),new BigDecimal("1"),null,TransactionSortOrder.RECENT,0,20));
        assertTrue(search(1,1001,null,null,null,"not-present",TransactionSortOrder.RECENT,0,20).empty());
    }

    private TransactionSearchResponse search(long customerId,long accountId,TransactionDirection direction,BigDecimal minimum,BigDecimal maximum,String keyword,TransactionSortOrder order,int page,int size) {
        return service.search(new ToolActor(customerId,ActorRole.CUSTOMER),new TransactionSearchRequest("a01-it-"+customerId+"-"+accountId+"-"+page,1L,customerId,accountId,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),direction,page,size,minimum,maximum,keyword,order));
    }
    private void insert(long id,long accountId,String at,String direction,String amount,String counterparty,String memo) { jdbc.update("INSERT INTO account_transaction(id,account_id,tx_at,direction,amount,counterparty,memo) VALUES(?,?,?,?,?,?,?)",id,accountId,at,direction,new BigDecimal(amount),counterparty,memo); }
}
