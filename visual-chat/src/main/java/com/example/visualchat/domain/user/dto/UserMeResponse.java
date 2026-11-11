package com.example.visualchat.domain.user.dto;

import com.example.visualchat.domain.user.entity.User;

import java.time.Instant;

/** [ROLE] 본인 계정 조회에 필요한 회원 정보만 전달 */
public record UserMeResponse(Long userId, String nickname, String email, Instant signupCompletedAt) {

    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                user.getSignupCompletedAt()
        );
    }
}