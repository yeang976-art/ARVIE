package com.example.visualchat.domain.message.service;

import com.example.visualchat.domain.message.dto.MessageResponse;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** [ROLE] 메시지 도메인의 유효한 AI 답변 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final MessageRepository messageRepository;

    // [TOPIC] 소유권 확인이 완료된 요청의 유효한 AI 답변 조회
    public Optional<MessageResponse> findVisibleAnswer(UUID requestId, Long roomId, Instant readAt) {
        return messageRepository
                .findByAiRequest_IdAndChatRoom_IdAndSenderAndExpiresAtGreaterThan(
                        requestId,
                        roomId,
                        SenderType.ASSISTANT,
                        readAt
                )
                .map(MessageResponse::from);
    }
}