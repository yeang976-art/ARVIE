package com.example.visualchat.domain.message.repository;

import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.message.entity.Message;
import com.example.visualchat.domain.message.enums.SenderType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** [ROLE] 메시지 저장과 조회 시점에 유효한 메시지 조회를 담당 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    // [WHY] 물리 삭제 전이라도 만료 시각과 같거나 지난 원문은 조회에서 제외
    Optional<Message> findByAiRequest_IdAndChatRoom_IdAndSenderAndExpiresAtGreaterThan(
            UUID requestId,
            Long roomId,
            SenderType sender,
            Instant readAt
    );

    // [TOPIC] 본인 채팅방의 유효한 최근 대화 조회
    // [WHY] 실패·처리 중인 요청의 메시지가 다음 대화 맥락에 섞이는 것을 방지
    @Query("""
        select m
        from Message m
        where m.chatRoom.id = :roomId
          and m.chatRoom.user.id = :userId
          and m.aiRequest.status = :status
          and m.sentAt <= :readAt
          and m.expiresAt > :readAt
        order by m.sentAt desc, m.id desc
        """)
    List<Message> findRecentContext(
            @Param("userId") Long userId,
            @Param("roomId") Long roomId,
            @Param("status") AiRequestStatus status,
            @Param("readAt") Instant readAt,
            Pageable pageable
    );
}