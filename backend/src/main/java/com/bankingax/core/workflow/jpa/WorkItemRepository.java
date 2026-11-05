package com.bankingax.core.workflow.jpa;

import com.bankingax.core.workflow.WorkItemStatus;
import java.util.Collection;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkItemRepository extends JpaRepository<WorkItemEntity, Long> {
    Optional<WorkItemEntity> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from WorkItemEntity item join WorkflowEntity flow on flow.id = item.workflowId "
            + "where flow.customerId = :customerId and item.taskCode = :taskCode and item.status = :status "
            + "order by item.id desc")
    java.util.List<WorkItemEntity> findActiveForCustomerAndTaskForUpdate(
            @Param("customerId") long customerId, @Param("taskCode") com.bankingax.core.workflow.TaskCode taskCode,
            @Param("status") WorkItemStatus status);

    @Query("select w.workflowId from WorkItemEntity w where w.id = :id")
    Optional<Long> findWorkflowIdById(@Param("id") long id);

    boolean existsByWorkflowIdAndStatus(long workflowId, WorkItemStatus status);

    boolean existsByWorkflowIdAndStatusIn(long workflowId, Collection<WorkItemStatus> statuses);

    @Query("select coalesce(max(w.sequenceNo), 0) from WorkItemEntity w where w.workflowId = :workflowId")
    int findMaxSequenceNoByWorkflowId(@Param("workflowId") long workflowId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkItemEntity w where w.id = :id")
    Optional<WorkItemEntity> findForUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkItemEntity w where w.id = :id and w.workflowId = :workflowId")
    Optional<WorkItemEntity> findForUpdateByIdAndWorkflowId(@Param("id") long id, @Param("workflowId") long workflowId);
}
