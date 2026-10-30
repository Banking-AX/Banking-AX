package com.bankingax.core.workflow.jpa;

import com.bankingax.core.workflow.TaskCode;
import com.bankingax.core.workflow.WorkItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "work_item")
class WorkItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "workflow_id", nullable = false)
    long workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_code", nullable = false, length = 40)
    TaskCode taskCode;

    @Column(name = "sequence_no", nullable = false)
    int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    WorkItemStatus status;

    @Column(name = "resume_step", length = 80)
    String resumeStep;

    @Column(name = "draft_data", columnDefinition = "json")
    String draftData;

    @Column(name = "failure_reason", length = 255)
    String failureReason;

    @Column(name = "cancel_reason", length = 60)
    String cancelReason;

    @Column(name = "request_id", nullable = false, unique = true, length = 90)
    String requestId;

    @Column(name = "last_activity_at", insertable = false)
    Instant lastActivityAt;

    @Column(name = "reset_at")
    Instant resetAt;

    @Version
    @Column(nullable = false)
    int version;

    @Column(name = "created_at", insertable = false, updatable = false)
    Instant createdAt;
}
