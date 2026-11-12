package com.example.visualchat.domain.message.service;

import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.message.dto.MessageResponse;
import com.example.visualchat.domain.message.entity.Message;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** [ROLE] 메시지 저장과 조회 시점에 유효한 대화 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    // [NOTE] 현재 사용자 메시지를 제외한 과거 메시지 개수
    private static final int RECENT_CONTEXT_LIMIT = 20;

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

    // [TOPIC] 사용자 메시지 또는 AI 답변 저장
    @Transactional
    public MessageResponse create(ChatRoom chatRoom, AiRequest aiRequest, SenderType sender, String content) {
        Message message = Message.of(chatRoom, aiRequest, sender, content);

        // [WHY] 중복 메시지 등 DB 제약 위반을 저장 단계에서 확인
        Message saved = messageRepository.saveAndFlush(message);

        return MessageResponse.from(saved);
    }

    // [TOPIC] AI 입력으로 사용할 유효한 최근 대화 조회
    public List<MessageResponse> findRecentContext(Long userId, Long roomId, Instant readAt) {
        List<Message> recent = messageRepository.findRecentContext(
                userId,
                roomId,
                AiRequestStatus.SUCCEEDED,
                readAt,
                PageRequest.of(0, RECENT_CONTEXT_LIMIT)
        );

        // [WHY] 최신 메시지부터 개수를 제한한 뒤 과거→현재 순서로 AI에 전달
        return recent.reversed().stream()
                .map(MessageResponse::from)
                .toList();
    }
}