package com.bankingax.core.workflow.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkflowRepository extends JpaRepository<WorkflowEntity, Long> {
    @Query(value = "select exists(select 1 from `user` u where u.id = :customerId and u.role = 'CUSTOMER' and u.is_active = true)", nativeQuery = true)
    int existsActiveCustomerById(@Param("customerId") long customerId);

    @Query(value = "select id from `user` where id = :customerId and role = 'CUSTOMER' and is_active = true for update", nativeQuery = true)
    Optional<Long> lockActiveCustomerById(@Param("customerId") long customerId);

    @Query(value = "select exists(select 1 from chat_room r where r.id = :chatRoomId)", nativeQuery = true)
    int existsChatRoomById(@Param("chatRoomId") long chatRoomId);

    @Query(value = "select exists(select 1 from chat_room r where r.id = :chatRoomId and r.customer_id = :customerId)", nativeQuery = true)
    int existsChatRoomOwnedByCustomerId(@Param("chatRoomId") long chatRoomId, @Param("customerId") long customerId);

    Optional<WorkflowEntity> findByIdAndCustomerId(long id, long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.id = :id")
    Optional<WorkflowEntity> findForUpdate(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowEntity w where w.id = :id and w.customerId = :customerId")
    Optional<WorkflowEntity> findForUpdateByIdAndCustomerId(@Param("id") long id, @Param("customerId") long customerId);
}
