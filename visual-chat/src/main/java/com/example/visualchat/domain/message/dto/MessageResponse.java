package com.example.visualchat.domain.message.dto;

import com.example.visualchat.domain.message.entity.Message;
import com.example.visualchat.domain.message.enums.SenderType;

import java.time.Instant;

/** [ROLE] 조회 가능한 메시지의 본문과 전송·만료 정보를 응답 */
public record MessageResponse(
        Long messageId,
        SenderType sender,
        String content,
        Instant sentAt,
        Instant expiresAt
) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getSender(),
                message.getContent(),
                message.getSentAt(),
                message.getExpiresAt()
        );
    }
}