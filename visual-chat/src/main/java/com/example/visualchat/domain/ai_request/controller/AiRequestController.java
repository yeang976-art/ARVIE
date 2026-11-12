package com.example.visualchat.domain.ai_request.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.service.AiRequestStatusCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** [ROLE] 인증된 회원의 AI 요청 상태 조회를 처리 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai-requests")
public class AiRequestController {

    private final AiRequestStatusCommand aiRequestStatusCommand;

    // [TOPIC] 본인 소유 요청의 처리 상태 조회
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<AiRequestStatusResponse>> getStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("requestId") UUID requestId
    ) {
        long userId = extractUserId(jwt);

        return ResponseEntity.ok(ApiResponse.ok(aiRequestStatusCommand.getStatus(userId, requestId)));
    }

    // [TOPIC] JWT의 회원 식별값을 양수 Long으로 변환
    private long extractUserId(Jwt jwt) {
        try {
            long userId = Long.parseLong(Objects.requireNonNull(jwt.getSubject()));

            if (userId <= 0) {
                throw new ServiceException(ErrorCode.INVALID_TOKEN);
            }
            return userId;

        } catch (NumberFormatException exception) {
            throw new ServiceException(ErrorCode.INVALID_TOKEN);
        }
    }
}