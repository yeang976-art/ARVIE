package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.message.dto.MessageResponse;
import com.example.visualchat.domain.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** [ROLE] 본인 요청 상태와 조회 가능한 AI 답변의 조회를 조율 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiRequestStatusCommand {

    private final Clock clock;
    private final MessageService messageService;
    private final AiRequestService aiRequestService;

    public AiRequestStatusResponse getStatus(Long userId, UUID requestId) {
        // [WHY] 메시지 조회 전에 요청의 소유권부터 확인
        AiRequestStatusResponse response = aiRequestService.getStatus(userId, requestId);

        if (response.status() != AiRequestStatus.SUCCEEDED) {
            return response;
        }

        // [NOTE] 이 조회의 만료 판정 시각을 한 번만 결정
        Instant readAt = clock.instant();

        MessageResponse answer = messageService
                .findVisibleAnswer(requestId, response.chatRoomId(), readAt)
                .orElse(null);

        // [CAUTION] 삭제·만료된 답변을 요청 기록이나 외부 AI에서 복원하지 않음
        return response.withAnswer(answer);
    }
}