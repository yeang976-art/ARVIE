package com.example.visualchat.domain.auth.model;

/** [ROLE] 인증된 회원의 식별값과 기본 정보를 전달하는 모델 */
public record AuthUser(Long userId, String nickname, String email) {
}
