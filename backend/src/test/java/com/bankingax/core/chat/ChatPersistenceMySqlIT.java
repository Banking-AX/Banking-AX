package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class ChatPersistenceMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_chat_persistence_it")
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
    ChatRoomRepository chatRooms;

    @Autowired
    ChatMessageRepository chatMessages;

    @Autowired
    JdbcTemplate jdbc;

    @PersistenceContext
    EntityManager entityManager;

    @BeforeEach
    void seedCustomers() {
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name) values"
                + "(1,'chat-customer-1','x','CUSTOMER','customer one'),"
                + "(2,'chat-customer-2','x','CUSTOMER','customer two')");
    }

    @Test
    void latestFlywayAndChatRoomOwnershipQueriesWork() {
        Integer version = jdbc.queryForObject(
                "select max(cast(version as unsigned)) from flyway_schema_history where success=1", Integer.class);
        ChatRoomEntity older = room(1, Instant.parse("2026-01-01T00:00:00Z"));
        ChatRoomEntity newer = room(1, Instant.parse("2026-01-02T00:00:00Z"));
        ChatRoomEntity anotherCustomer = room(2, Instant.parse("2026-01-03T00:00:00Z"));

        assertThat(version).isEqualTo(13);
        assertThat(chatRooms.findByIdAndCustomerId(older.id, 1)).isPresent();
        assertThat(chatRooms.findByIdAndCustomerId(older.id, 2)).isEmpty();
        assertThat(chatRooms.findAllByCustomerIdOrderByLastActivityAtDescIdDesc(1))
                .extracting(room -> room.id)
                .containsExactly(newer.id, older.id);
        assertThat(chatRooms.findAllByCustomerIdOrderByLastActivityAtDescIdDesc(2))
                .extracting(room -> room.id)
                .containsExactly(anotherCustomer.id);
        assertThat(chatRooms.findById(older.id).orElseThrow().createdAt).isNotNull();
    }

    @Test
    @Transactional
    void chatRoomCanBeLockedByOwnerForFutureSequenceAllocation() {
        ChatRoomEntity room = room(1, Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(chatRooms.findForUpdateByIdAndCustomerId(room.id, 1)).isPresent();
        assertThat(chatRooms.findForUpdateByIdAndCustomerId(room.id, 2)).isEmpty();
    }

    @Test
    void messagesAreReadInSequenceAndMaxSequenceDoesNotLoadEveryMessage() {
        ChatRoomEntity room = room(1, Instant.parse("2026-01-01T00:00:00Z"));
        message(room.id, 2, ChatSenderType.AGENT, "answer", null, "[{\"title\":\"official guide\"}]");
        message(room.id, 1, ChatSenderType.CUSTOMER, "question", "request-1", null);

        assertThat(chatMessages.findAllByChatRoomIdOrderBySeqAsc(room.id))
                .extracting(message -> message.seq)
                .containsExactly(1, 2);
        assertThat(chatMessages.findMaxSeqByChatRoomId(room.id)).isEqualTo(2);
        assertThat(chatMessages.existsByChatRoomId(room.id)).isTrue();
        assertThat(chatMessages.findByChatRoomIdAndRequestId(room.id, "request-1"))
                .get()
                .extracting(message -> message.content)
                .isEqualTo("question");
        assertThat(jdbc.queryForObject(
                "select json_unquote(json_extract(citations,'$[0].title')) "
                        + "from chat_message where chat_room_id=? and seq=2",
                String.class,
                room.id))
                .isEqualTo("official guide");
    }

    @Test
    void databaseEnforcesSequenceAndRequestIdUniquenessPerRoom() {
        ChatRoomEntity firstRoom = room(1, Instant.parse("2026-01-01T00:00:00Z"));
        ChatRoomEntity secondRoom = room(1, Instant.parse("2026-01-02T00:00:00Z"));
        message(firstRoom.id, 1, ChatSenderType.CUSTOMER, "first", "same-request", null);

        assertThatThrownBy(() -> jdbc.update(
                "insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                        + "values(?,null,1,'AGENT','duplicate seq')",
                firstRoom.id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into chat_message(chat_room_id,request_id,seq,sender_type,content) "
                        + "values(?,'same-request',2,'CUSTOMER','duplicate request')",
                firstRoom.id))
                .isInstanceOf(DataIntegrityViolationException.class);

        message(secondRoom.id, 1, ChatSenderType.CUSTOMER, "allowed", "same-request", null);
        assertThat(chatMessages.findByChatRoomIdAndRequestId(secondRoom.id, "same-request")).isPresent();
    }

    @Test
    @Transactional
    void agentAndSystemMessagesAllowNullRequestIdAndUseSentDefault() {
        ChatRoomEntity room = room(1, Instant.parse("2026-01-01T00:00:00Z"));
        ChatMessageEntity agent = message(room.id, 1, ChatSenderType.AGENT, "agent", null, null);
        ChatMessageEntity system = message(room.id, 2, ChatSenderType.SYSTEM, "system", null, null);
        entityManager.refresh(agent);
        entityManager.refresh(system);

        assertThat(chatMessages.findAllByChatRoomIdOrderBySeqAsc(room.id))
                .extracting(message -> message.requestId)
                .containsExactly(null, null);
        assertThat(agent.status).isEqualTo(ChatMessageStatus.SENT);
        assertThat(system.status).isEqualTo(ChatMessageStatus.SENT);
        assertThat(agent.sentAt).isNotNull();
        assertThat(system.sentAt).isNotNull();
    }

    private ChatRoomEntity room(long customerId, Instant lastActivityAt) {
        ChatRoomEntity room = new ChatRoomEntity();
        room.customerId = customerId;
        room.lastActivityAt = lastActivityAt;
        return chatRooms.saveAndFlush(room);
    }

    private ChatMessageEntity message(
            long chatRoomId,
            int seq,
            ChatSenderType senderType,
            String content,
            String requestId,
            String citations) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.chatRoomId = chatRoomId;
        message.seq = seq;
        message.senderType = senderType;
        message.content = content;
        message.requestId = requestId;
        message.citations = citations;
        return chatMessages.saveAndFlush(message);
    }
}
