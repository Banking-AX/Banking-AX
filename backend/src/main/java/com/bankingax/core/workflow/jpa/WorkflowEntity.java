package com.bankingax.core.workflow.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow")
class WorkflowEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "chat_room_id")
    Long chatRoomId;

    @Column(name = "customer_id", nullable = false)
    long customerId;

    @Column(nullable = false, length = 24)
    String status;

    @Version
    @Column(nullable = false)
    int version;

    @Column(name = "created_at", insertable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    LocalDateTime updatedAt;
}
