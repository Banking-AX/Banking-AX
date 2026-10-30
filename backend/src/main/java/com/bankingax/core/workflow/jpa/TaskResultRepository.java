package com.bankingax.core.workflow.jpa;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TaskResultRepository extends JpaRepository<TaskResultEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from TaskResultEntity r where r.workItemId = :workItemId")
    Optional<TaskResultEntity> findForReadByWorkItemId(@Param("workItemId") long workItemId);
}
