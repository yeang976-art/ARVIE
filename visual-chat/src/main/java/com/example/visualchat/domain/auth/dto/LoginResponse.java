package com.example.visualchat.domain.auth.dto;

import com.example.visualchat.domain.auth.model.IssuedToken;

import java.time.Instant;

/** [ROLE] 로그인에 성공한 클라이언트에 토큰과 만료 시각을 전달 */
public record LoginResponse(String token, Instant expiresAt) {

    public static LoginResponse from(IssuedToken issuedToken) {
        return new LoginResponse(issuedToken.token(), issuedToken.expiresAt());
    }

    // [CAUTION] 응답 객체 출력 시 인증 토큰이 로그에 노출되지 않도록 제외
    @Override
    public String toString() {
        return "LoginResponse[expiresAt=" + expiresAt + "]";
    }
}
