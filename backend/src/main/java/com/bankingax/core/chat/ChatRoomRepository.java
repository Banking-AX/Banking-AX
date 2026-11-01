package com.bankingax.core.chat;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ChatRoomRepository extends JpaRepository<ChatRoomEntity, Long> {
    @Query(value = "select exists(select 1 from `user` u "
            + "where u.id = :customerId and u.role = 'CUSTOMER' and u.is_active = true)", nativeQuery = true)
    int existsActiveCustomerById(@Param("customerId") long customerId);

    Optional<ChatRoomEntity> findByIdAndCustomerId(long id, long customerId);

    List<ChatRoomEntity> findAllByCustomerIdOrderByLastActivityAtDescIdDesc(long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoomEntity r where r.id = :id and r.customerId = :customerId")
    Optional<ChatRoomEntity> findForUpdateByIdAndCustomerId(
            @Param("id") long id, @Param("customerId") long customerId);
}
