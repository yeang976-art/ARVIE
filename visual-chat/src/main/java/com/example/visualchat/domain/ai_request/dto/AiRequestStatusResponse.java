package com.example.visualchat.domain.ai_request.dto;

import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.message.dto.MessageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** [ROLE] AI 요청 상태와 현재 조회 가능한 답변을 응답 */
public record AiRequestStatusResponse(
        UUID requestId,
        Long chatRoomId,
        AiRequestStatus status,
        BigDecimal chargedVie,
        String errorCode,
        Instant startedAt,
        Instant processingDeadlineAt,
        Instant finishedAt,
        MessageResponse answer
) {
    public static AiRequestStatusResponse from(AiRequest request) {
        return new AiRequestStatusResponse(
                request.getId(),
                request.getChatRoomId(),
                request.getStatus(),
                request.getChargedVie(),
                request.getErrorCode(),
                request.getStartedAt(),
                request.getProcessingDeadlineAt(),
                request.getFinishedAt(),
                null
        );
    }

    public AiRequestStatusResponse withAnswer(MessageResponse answer) {
        return new AiRequestStatusResponse(
                requestId,
                chatRoomId,
                status,
                chargedVie,
                errorCode,
                startedAt,
                processingDeadlineAt,
                finishedAt,
                answer
        );
    }
}