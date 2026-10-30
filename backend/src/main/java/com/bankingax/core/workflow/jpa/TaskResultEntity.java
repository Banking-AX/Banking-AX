package com.bankingax.core.workflow.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_result")
class TaskResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "work_item_id", nullable = false, unique = true)
    long workItemId;

    @Column(name = "result_type", nullable = false, length = 40)
    String resultType;

    @Column(name = "target_loan_id")
    Long targetLoanId;

    @Column(columnDefinition = "json")
    String detail;

    @Column(name = "created_at", insertable = false, updatable = false)
    LocalDateTime createdAt;
}
