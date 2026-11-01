package com.bankingax.core.chat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findAllByChatRoomIdOrderBySeqAsc(long chatRoomId);

    @Query("select coalesce(max(m.seq), 0) from ChatMessageEntity m where m.chatRoomId = :chatRoomId")
    int findMaxSeqByChatRoomId(@Param("chatRoomId") long chatRoomId);

    Optional<ChatMessageEntity> findByChatRoomIdAndRequestId(long chatRoomId, String requestId);

    boolean existsByChatRoomId(long chatRoomId);
}
