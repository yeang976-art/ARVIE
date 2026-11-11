package com.example.visualchat.domain.user.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.domain.user.dto.UserMeResponse;
import com.example.visualchat.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [ROLE] 인증된 회원의 본인 정보 조회 요청을 처리 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserMeResponse>> getMyInfo(@AuthenticationPrincipal Jwt jwt) {
        // [WHY] 조회 대상은 검증된 인증 정보에서 결정해 타인 정보 조회를 방지
        long userId = extractUserId(jwt);

        return ResponseEntity.ok(ApiResponse.ok(userService.getMyInfo(userId)));
    }

    // [TOPIC] JWT의 회원 식별값을 양수 Long으로 변환
    private long extractUserId(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());

            if (userId <= 0) {
                throw new ServiceException(ErrorCode.INVALID_TOKEN);
            }
            return userId;

        } catch (NumberFormatException e) {
            // [CAUTION] 숫자가 아니거나 Long 범위를 벗어난 식별값은 인증 오류로 처리
            throw new ServiceException(ErrorCode.INVALID_TOKEN);
        }
    }
}