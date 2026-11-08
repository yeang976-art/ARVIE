package com.example.visualchat.domain.auth.model;

import java.time.Instant;

/** [ROLE] 발급 결과를 담음 */
public record IssuedToken(String token, Instant expiresAt) {
    // [CAUTION] 객체 출력 시 인증 토큰이 로그에 노출되지 않도록 제외
    @Override
    public String toString() {
        return "IssuedToken[expiresAt=" + expiresAt + "]";
    }
}
