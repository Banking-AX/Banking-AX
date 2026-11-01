package com.bankingax.core.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "chat_message",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_chatmessage_seq", columnNames = {"chat_room_id", "seq"}),
            @UniqueConstraint(name = "uk_chatmessage_request", columnNames = {"chat_room_id", "request_id"})
        })
class ChatMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "chat_room_id", nullable = false)
    long chatRoomId;

    @Column(name = "request_id", length = 64)
    String requestId;

    @Column(nullable = false)
    int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 16)
    ChatSenderType senderType;

    @Column(nullable = false, columnDefinition = "mediumtext")
    String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    ChatMessageStatus status = ChatMessageStatus.SENT;

    @Column(columnDefinition = "json")
    String citations;

    @Column(name = "sent_at", insertable = false, updatable = false)
    Instant sentAt;
}
