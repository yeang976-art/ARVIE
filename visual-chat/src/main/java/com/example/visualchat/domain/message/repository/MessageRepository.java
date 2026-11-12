package com.example.visualchat.domain.message.repository;

import com.example.visualchat.domain.message.entity.Message;
import com.example.visualchat.domain.message.enums.SenderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
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
}