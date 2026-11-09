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

    /**
     * A submitted/reviewed C Case can retain its WorkItem audit state, but it is
     * not resumable customer work.  Keep draft C Cases resumable and leave all
     * non-Case tasks unchanged.
     */
    @Query(value = "select wi.* from work_item wi join workflow wf on wf.id=wi.workflow_id "
            + "left join service_case sc on sc.work_item_id=wi.id "
            + "where wf.customer_id=:customerId and wi.task_code=:taskCode and wi.status=:status "
            + "and (sc.id is null or sc.case_status='DRAFT') order by wi.id desc for update", nativeQuery = true)
    java.util.List<WorkItemEntity> findResumableActiveForCustomerAndTaskForUpdate(
            @Param("customerId") long customerId, @Param("taskCode") String taskCode,
            @Param("status") String status);

    @Query(value = "select wi.* from work_item wi join workflow wf on wf.id=wi.workflow_id "
            + "where wf.customer_id=:customerId and wf.chat_room_id=:chatRoomId "
            + "and wi.task_code=:taskCode and wi.status in ('ACTIVE','ON_HOLD') "
            + "order by wi.id desc limit 1", nativeQuery = true)
    Optional<WorkItemEntity> findLatestLinkedUnfinished(
            @Param("customerId") long customerId,
            @Param("chatRoomId") long chatRoomId,
            @Param("taskCode") String taskCode);

    @Query(value = "select wi.* from work_item wi join workflow wf on wf.id=wi.workflow_id "
            + "where wf.customer_id=:customerId "
            + "and (wf.chat_room_id is null or wf.chat_room_id<>:chatRoomId) "
            + "and wi.task_code=:taskCode and wi.status in ('ACTIVE','ON_HOLD') "
            + "order by case when wi.status='ACTIVE' then 0 else 1 end, wi.id desc limit 1",
            nativeQuery = true)
    Optional<WorkItemEntity> findLatestUnfinishedInAnotherChat(
            @Param("customerId") long customerId,
            @Param("chatRoomId") long chatRoomId,
            @Param("taskCode") String taskCode);

    @Query(value = "select wi.* from work_item wi join workflow wf on wf.id=wi.workflow_id "
            + "where wf.customer_id=:customerId "
            + "and (wf.chat_room_id is null or wf.chat_room_id<>:chatRoomId) "
            + "and wi.task_code=:taskCode and wi.status in ('ACTIVE','ON_HOLD') "
            + "order by case when wi.status='ACTIVE' then 0 else 1 end, wi.id desc",
            nativeQuery = true)
    java.util.List<WorkItemEntity> findAllUnfinishedInAnotherChat(
            @Param("customerId") long customerId,
            @Param("chatRoomId") long chatRoomId,
            @Param("taskCode") String taskCode);

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
