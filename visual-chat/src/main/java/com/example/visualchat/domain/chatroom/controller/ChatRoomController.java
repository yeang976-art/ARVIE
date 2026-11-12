package com.example.visualchat.domain.chatroom.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.domain.chatroom.dto.ChatRoomResponse;
import com.example.visualchat.domain.chatroom.service.ChatRoomCommand;
import com.example.visualchat.domain.chatroom.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** [ROLE] 인증된 회원의 채팅방 열기와 보관 설정 조회 요청을 처리 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ChatRoomController {

    private final ChatRoomCommand chatRoomCommand;
    private final ChatRoomService chatRoomService;

    // [TOPIC] 배정된 레브의 채팅방 열기
    @PostMapping("/reves/{reveId}/chat-rooms")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> open(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("reveId") Long reveId
    ) {
        long userId = extractUserId(jwt);
        validateResourceId(reveId);

        return ResponseEntity.ok(ApiResponse.ok(chatRoomCommand.open(userId, reveId)));
    }

    // [TOPIC] 본인 소유 채팅방의 보관 설정 조회
    @GetMapping("/chat-rooms/{roomId}/settings")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> getSettings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("roomId") Long roomId
    ) {
        long userId = extractUserId(jwt);
        validateResourceId(roomId);

        return ResponseEntity.ok(ApiResponse.ok(chatRoomService.getSettings(userId, roomId)));
    }

    // [TOPIC] JWT의 회원 식별값을 양수 Long으로 변환
    private long extractUserId(Jwt jwt) {
        try {
            long userId = Long.parseLong(Objects.requireNonNull(jwt.getSubject()));

            if (userId <= 0) {
                throw new ServiceException(ErrorCode.INVALID_TOKEN);
            }
            return userId;

        } catch (NumberFormatException e) {
            throw new ServiceException(ErrorCode.INVALID_TOKEN);
        }
    }

    // [TOPIC] 요청 경로의 리소스 식별값 검증
    private void validateResourceId(long id) {
        if (id <= 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT);
        }
    }
}