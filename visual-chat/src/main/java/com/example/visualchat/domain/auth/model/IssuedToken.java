package com.example.visualchat.domain.auth.model;

import java.time.Instant;

/** [ROLE] 발급된 Access Token과 만료 시각을 전달하는 모델 */
public record IssuedToken(String token, Instant expiresAt) {
    // [CAUTION] 객체 출력 시 인증 토큰이 로그에 노출되지 않도록 제외
    @Override
    public String toString() {
        return "IssuedToken[expiresAt=" + expiresAt + "]";
    }
}
