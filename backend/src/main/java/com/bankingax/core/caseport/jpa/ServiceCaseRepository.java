package com.bankingax.core.caseport.jpa;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface ServiceCaseRepository extends JpaRepository<ServiceCaseEntity,Long> {
    Optional<ServiceCaseEntity> findByWorkItemId(long workItemId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ServiceCaseEntity c where c.id=:id")
    Optional<ServiceCaseEntity> findForUpdate(@Param("id") long id);
}
