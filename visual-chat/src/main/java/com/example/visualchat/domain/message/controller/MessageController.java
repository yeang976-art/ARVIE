package com.example.visualchat.domain.message.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.service.AiChatCommand;
import com.example.visualchat.domain.message.dto.SendMessageRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [ROLE] 인증된 회원의 메시지 전송 요청과 처리 결과 응답을 담당 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/{roomId}/messages")
public class MessageController {

    private final AiChatCommand aiChatCommand;

    // [TOPIC] 레브에게 메시지 전송
    @PostMapping
    public ResponseEntity<ApiResponse<AiRequestStatusResponse>> send(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("roomId") Long roomId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendMessageRequest request
    ) {
        long userId = extractUserId(jwt);

        if (roomId == null || roomId <= 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT);
        }

        AiRequestStatusResponse response = aiChatCommand.send(userId, roomId, idempotencyKey, request.content());

        // [NOTE] 같은 키의 기존 요청이 처리 중이면 접수 상태를 반환하고 재생성하지 않음
        HttpStatus status = response.status() == AiRequestStatus.PROCESSING
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;

        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    // [TOPIC] JWT의 회원 식별값을 양수 Long으로 변환
    private long extractUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ServiceException(ErrorCode.INVALID_TOKEN);
        }

        try {
            long userId = Long.parseLong(jwt.getSubject());

            if (userId <= 0) {
                throw new ServiceException(ErrorCode.INVALID_TOKEN);
            }
            return userId;

        } catch (NumberFormatException exception) {
            throw new ServiceException(ErrorCode.INVALID_TOKEN);
        }
    }
}