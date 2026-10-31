package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
@SpringBootTest
class ChatMessageMigrationMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_chat_message_it")
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
    void seedRooms() {
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) "
                + "values(1,'chat-customer','x','CUSTOMER','customer')");
        jdbc.update("insert into chat_room(id,customer_id,last_activity_at) values"
                + "(10,1,utc_timestamp(6)),(20,1,utc_timestamp(6))");
    }

    @Test
    void flywayV1ThroughV6CreatesTheExpectedTableShape() {
        Integer version = jdbc.queryForObject(
                "select max(cast(version as unsigned)) from flyway_schema_history where success=1", Integer.class);
        List<Map<String, Object>> columns = jdbc.queryForList(
                "select column_name,data_type,column_type,is_nullable,column_default "
                        + "from information_schema.columns where table_schema=database() "
                        + "and table_name='chat_message' order by ordinal_position");
        List<String> indexes = jdbc.queryForList(
                "select distinct index_name from information_schema.statistics "
                        + "where table_schema=database() and table_name='chat_message'", String.class);
        List<String> constraints = jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints "
                        + "where constraint_schema=database() and table_name='chat_message'", String.class);

        assertThat(version).isEqualTo(6);
        assertThat(columns).extracting(row -> row.get("COLUMN_NAME")).containsExactly(
                "id", "chat_room_id", "request_id", "seq", "sender_type", "content", "status", "citations", "sent_at");
        assertThat(columns).extracting(row -> row.get("COLUMN_TYPE")).containsExactly(
                "bigint unsigned", "bigint unsigned", "varchar(64)", "int unsigned", "varchar(16)",
                "mediumtext", "varchar(20)", "json", "datetime(6)");
        assertThat(indexes).contains(
                "PRIMARY", "uk_chatmessage_seq", "uk_chatmessage_request", "idx_chatmessage_expiry");
        assertThat(constraints).contains("fk_chatmessage_room", "ck_chatmessage_sender");
    }

    @Test
    void requestIdIsUniquePerRoomButCanBeReusedAcrossRoomsOrLeftNull() {
        jdbc.update("insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                + "values(10,'request-1',1,'CUSTOMER','first')");

        assertThatThrownBy(() -> jdbc.update(
                "insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                        + "values(10,'request-1',2,'CUSTOMER','duplicate request')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                + "values(20,'request-1',1,'CUSTOMER','same request in another room')");
        jdbc.update("insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                + "values(10,null,3,'AGENT','first null request')");
        jdbc.update("insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                + "values(10,null,4,'AGENT','second null request')");

        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where request_id='request-1'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=10 and request_id is null", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void foreignKeyAndPerRoomSequenceUniquenessAreEnforced() {
        jdbc.update("insert into chat_message(chat_room_id,seq,sender_type,content) "
                + "values(10,1,'CUSTOMER','first')");

        assertThatThrownBy(() -> jdbc.update(
                "insert into chat_message(chat_room_id,seq,sender_type,content) "
                        + "values(999,1,'CUSTOMER','missing room')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into chat_message(chat_room_id,seq,sender_type,content) "
                        + "values(10,1,'AGENT','duplicate sequence')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("insert into chat_message(chat_room_id,seq,sender_type,content) "
                + "values(20,1,'AGENT','same sequence in another room')");
        assertThat(jdbc.queryForObject("select count(*) from chat_message where seq=1", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void jsonAndDefaultsWorkAndRoomDeletionIsRestricted() {
        jdbc.update("insert into chat_message(chat_room_id,seq,sender_type,content,citations) "
                        + "values(10,1,'AGENT','answer',cast(? as json))",
                "[{\"title\":\"official guide\",\"page\":3}]");

        assertThat(jdbc.queryForObject(
                "select status from chat_message where chat_room_id=10 and seq=1", String.class))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "select json_unquote(json_extract(citations,'$[0].title')) "
                        + "from chat_message where chat_room_id=10 and seq=1", String.class))
                .isEqualTo("official guide");
        assertThat(jdbc.queryForObject(
                "select sent_at is not null from chat_message where chat_room_id=10 and seq=1", Boolean.class))
                .isTrue();
        assertThatThrownBy(() -> jdbc.update("delete from chat_room where id=10"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
