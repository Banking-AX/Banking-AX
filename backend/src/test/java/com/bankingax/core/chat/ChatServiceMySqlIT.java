package com.bankingax.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bankingax.core.api.DomainException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@SpringBootTest
class ChatServiceMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("banking_ax_chat_service_it")
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
    ChatService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedCustomers() {
        jdbc.update("delete from chat_message");
        jdbc.update("delete from chat_room");
        jdbc.update("delete from `user`");
        jdbc.update("insert into `user`(id,login_id,password_hash,`role`,name,is_active) values"
                + "(1,'chat-service-1','x','CUSTOMER','customer one',true),"
                + "(2,'chat-service-2','x','CUSTOMER','customer two',true),"
                + "(3,'chat-service-disabled','x','CUSTOMER','disabled',false)");
    }

    @Test
    void createsAndListsOnlyTheCustomersRooms() {
        ChatService.ChatRoomView first = service.createRoom(1);
        ChatService.ChatRoomView second = service.createRoom(1);
        ChatService.ChatRoomView other = service.createRoom(2);

        assertThat(first.createdAt()).isNotNull();
        assertThat(service.getRoom(1, first.id())).isEqualTo(first);
        assertThat(service.listRooms(1)).extracting(ChatService.ChatRoomView::id)
                .containsExactly(second.id(), first.id());
        assertThat(service.listRooms(2)).extracting(ChatService.ChatRoomView::id)
                .containsExactly(other.id());
        assertThatThrownBy(() -> service.createRoom(3))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void rejectsAnotherCustomersRoomForReadAndWrite() {
        long roomId = service.createRoom(1).id();

        assertThatThrownBy(() -> service.getRoom(2, roomId))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CHAT_ROOM_NOT_AVAILABLE"));
        assertThatThrownBy(() -> service.listMessages(2, roomId))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CHAT_ROOM_NOT_AVAILABLE"));
        assertThatThrownBy(() -> service.saveCustomerMessage(2, roomId, "foreign-1", "blocked"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CHAT_ROOM_NOT_AVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from chat_message", Integer.class)).isZero();
    }

    @Test
    void assignsIncreasingSequenceAndReturnsMessagesInOrder() {
        long roomId = service.createRoom(1).id();

        ChatService.ChatMessageView first = service.saveCustomerMessage(1, roomId, "message-1", "first");
        ChatService.ChatMessageView second = service.saveCustomerMessage(1, roomId, "message-2", "second");

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.seq()).isEqualTo(2);
        assertThat(first.sentAt()).isNotNull();
        assertThat(service.listMessages(1, roomId))
                .extracting(ChatService.ChatMessageView::content)
                .containsExactly("first", "second");
        assertThat(service.getRoom(1, roomId).lastActivityAt()).isNotNull();
    }

    @Test
    void serializesConcurrentMessagesWithinOneRoomWithoutDuplicateSequence() throws Exception {
        long roomId = service.createRoom(1).id();
        int messageCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(messageCount);
        CountDownLatch ready = new CountDownLatch(messageCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ChatService.ChatMessageView>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < messageCount; index++) {
                int messageIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return service.saveCustomerMessage(
                            1, roomId, "concurrent-" + messageIndex, "message " + messageIndex);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<ChatService.ChatMessageView> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(service.listMessages(1, roomId))
                .extracting(ChatService.ChatMessageView::seq)
                .containsExactly(1, 2, 3, 4, 5, 6)
                .doesNotHaveDuplicates();
    }

    @Test
    void differentRoomsAllocateTheirSequencesIndependently() throws Exception {
        long firstRoom = service.createRoom(1).id();
        long secondRoom = service.createRoom(1).id();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ChatService.ChatMessageView> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.saveCustomerMessage(1, firstRoom, "room-1", "first room");
            });
            Future<ChatService.ChatMessageView> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.saveCustomerMessage(1, secondRoom, "room-2", "second room");
            });
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).seq()).isEqualTo(1);
            assertThat(second.get(20, TimeUnit.SECONDS).seq()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void identicalRequestReturnsTheExistingMessageWithoutExtendingActivity() {
        long roomId = service.createRoom(1).id();
        ChatService.ChatMessageView first = service.saveCustomerMessage(1, roomId, "replay-1", "same");
        Timestamp activityBefore = jdbc.queryForObject(
                "select last_activity_at from chat_room where id=?", Timestamp.class, roomId);

        ChatService.ChatMessageView replay = service.saveCustomerMessage(1, roomId, "replay-1", "same");
        Timestamp activityAfter = jdbc.queryForObject(
                "select last_activity_at from chat_room where id=?", Timestamp.class, roomId);

        assertThat(replay).isEqualTo(first);
        assertThat(activityAfter).isEqualTo(activityBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_message where chat_room_id=?", Integer.class, roomId))
                .isEqualTo(1);
    }

    @Test
    void reusedRequestIdWithDifferentContentIsRejectedWithoutCreatingASequence() {
        long roomId = service.createRoom(1).id();
        service.saveCustomerMessage(1, roomId, "conflict-1", "original");

        assertThatThrownBy(() -> service.saveCustomerMessage(1, roomId, "conflict-1", "changed"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("DUPLICATE_REQUEST_CONFLICT"));
        assertThat(service.listMessages(1, roomId))
                .extracting(ChatService.ChatMessageView::seq)
                .containsExactly(1);
    }

    @Test
    void storesAgentAndSystemMessagesWithoutClientSelectableSender() {
        long roomId = service.createRoom(1).id();

        ChatService.ChatMessageView agent = service.saveAgentMessage(
                1, roomId, null, "agent answer", "[{\"title\":\"guide\"}]");
        ChatService.ChatMessageView system = service.saveSystemMessage(
                1, roomId, null, "system notice", null);

        assertThat(agent.senderType()).isEqualTo(ChatSenderType.AGENT);
        assertThat(agent.requestId()).isNull();
        assertThat(system.senderType()).isEqualTo(ChatSenderType.SYSTEM);
        assertThat(system.requestId()).isNull();
        assertThat(service.listMessages(1, roomId))
                .extracting(ChatService.ChatMessageView::senderType)
                .containsExactly(ChatSenderType.AGENT, ChatSenderType.SYSTEM);
    }
}
