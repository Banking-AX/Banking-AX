package com.bankingax.core.chat;

import com.bankingax.core.api.DomainException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
    private final ObjectProvider<ChatRoomRepository> chatRooms;
    private final ObjectProvider<ChatMessageRepository> chatMessages;
    private final ObjectProvider<EntityManager> entityManager;

    public ChatService(
            ObjectProvider<ChatRoomRepository> chatRooms,
            ObjectProvider<ChatMessageRepository> chatMessages,
            ObjectProvider<EntityManager> entityManager) {
        this.chatRooms = chatRooms;
        this.chatMessages = chatMessages;
        this.entityManager = entityManager;
    }

    @Transactional
    public ChatRoomView createRoom(long customerId) {
        ChatRoomRepository rooms = rooms();
        if (rooms.existsActiveCustomerById(customerId) == 0) {
            throw new DomainException("CUSTOMER_NOT_FOUND", "Active customer was not found.");
        }
        ChatRoomEntity room = new ChatRoomEntity();
        room.customerId = customerId;
        room = rooms.saveAndFlush(room);
        entityManager().refresh(room);
        return roomView(room);
    }

    @Transactional(readOnly = true)
    public List<ChatRoomView> listRooms(long customerId) {
        return rooms().findAllByCustomerIdOrderByLastActivityAtDescIdDesc(customerId).stream()
                .map(ChatService::roomView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatRoomView getRoom(long customerId, long chatRoomId) {
        return roomView(ownedRoom(customerId, chatRoomId));
    }

    @Transactional
    public ChatMessageView saveCustomerMessage(
            long customerId, long chatRoomId, String requestId, String content) {
        requireRequestId(requestId);
        return saveMessage(customerId, chatRoomId, ChatSenderType.CUSTOMER, requestId, content, null);
    }

    @Transactional
    public ChatMessageView saveAgentMessage(
            long customerId, long chatRoomId, String requestId, String content, String citations) {
        validateOptionalRequestId(requestId);
        return saveMessage(customerId, chatRoomId, ChatSenderType.AGENT, requestId, content, citations);
    }

    @Transactional
    public ChatMessageView saveSystemMessage(
            long customerId, long chatRoomId, String requestId, String content, String citations) {
        validateOptionalRequestId(requestId);
        return saveMessage(customerId, chatRoomId, ChatSenderType.SYSTEM, requestId, content, citations);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageView> listMessages(long customerId, long chatRoomId) {
        ownedRoom(customerId, chatRoomId);
        return messages().findAllByChatRoomIdOrderBySeqAsc(chatRoomId).stream()
                .map(ChatService::messageView)
                .toList();
    }

    private ChatMessageView saveMessage(
            long customerId,
            long chatRoomId,
            ChatSenderType senderType,
            String requestId,
            String content,
            String citations) {
        requireContent(content);
        ChatRoomRepository rooms = rooms();
        ChatMessageRepository messages = messages();
        ChatRoomEntity room = rooms.findForUpdateByIdAndCustomerId(chatRoomId, customerId)
                .orElseThrow(ChatService::unavailableRoom);

        if (requestId != null) {
            var replay = messages.findByChatRoomIdAndRequestId(chatRoomId, requestId);
            if (replay.isPresent()) {
                return replay(replay.get(), senderType, content, citations);
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ChatMessageEntity message = new ChatMessageEntity();
        message.chatRoomId = chatRoomId;
        message.requestId = requestId;
        message.seq = messages.findMaxSeqByChatRoomId(chatRoomId) + 1;
        message.senderType = senderType;
        message.content = content;
        message.citations = citations;
        room.lastActivityAt = now;

        try {
            message = messages.saveAndFlush(message);
            rooms.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw new DomainException(
                    "CHAT_MESSAGE_CONFLICT", "Chat message sequence or requestId conflicted.", true);
        }
        entityManager().refresh(message);
        return messageView(message);
    }

    private ChatRoomEntity ownedRoom(long customerId, long chatRoomId) {
        return rooms().findByIdAndCustomerId(chatRoomId, customerId)
                .orElseThrow(ChatService::unavailableRoom);
    }

    private ChatRoomRepository rooms() {
        return chatRooms.getIfAvailable(() -> {
            throw persistenceUnavailable();
        });
    }

    private ChatMessageRepository messages() {
        return chatMessages.getIfAvailable(() -> {
            throw persistenceUnavailable();
        });
    }

    private EntityManager entityManager() {
        return entityManager.getIfAvailable(() -> {
            throw persistenceUnavailable();
        });
    }

    private static ChatMessageView replay(
            ChatMessageEntity existing,
            ChatSenderType senderType,
            String content,
            String citations) {
        if (existing.senderType != senderType
                || !existing.content.equals(content)
                || !Objects.equals(existing.citations, citations)) {
            throw new DomainException(
                    "DUPLICATE_REQUEST_CONFLICT", "requestId was reused for another chat message.");
        }
        return messageView(existing);
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new DomainException("INVALID_REQUEST", "A requestId of at most 64 characters is required.");
        }
    }

    private static void validateOptionalRequestId(String requestId) {
        if (requestId != null && (requestId.isBlank() || requestId.length() > 64)) {
            throw new DomainException("INVALID_REQUEST", "requestId must be null or at most 64 characters.");
        }
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new DomainException("INVALID_REQUEST", "Chat message content is required.");
        }
    }

    private static DomainException unavailableRoom() {
        return new DomainException("CHAT_ROOM_NOT_AVAILABLE", "Chat room is not available.");
    }

    private static DomainException persistenceUnavailable() {
        return new DomainException(
                "CHAT_PERSISTENCE_UNAVAILABLE", "Chat persistence is not configured.", true);
    }

    private static ChatRoomView roomView(ChatRoomEntity room) {
        return new ChatRoomView(
                room.id, room.customerId, room.createdAt, room.lastActivityAt, room.oldestRetainedAt);
    }

    private static ChatMessageView messageView(ChatMessageEntity message) {
        return new ChatMessageView(
                message.id,
                message.chatRoomId,
                message.seq,
                message.senderType,
                message.content,
                message.status,
                message.citations,
                message.sentAt,
                message.requestId);
    }

    public record ChatRoomView(
            long id,
            long customerId,
            Instant createdAt,
            Instant lastActivityAt,
            Instant oldestRetainedAt) { }

    public record ChatMessageView(
            long id,
            long chatRoomId,
            int seq,
            ChatSenderType senderType,
            String content,
            ChatMessageStatus status,
            String citations,
            Instant sentAt,
            String requestId) { }
}
