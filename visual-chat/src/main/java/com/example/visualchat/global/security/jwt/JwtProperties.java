package com.example.visualchat.global.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** [ROLE] 외부 JWT 설정의 발급자·서명 키·유효기간을 바인딩하고 필수값을 검증 */
@Validated // [WHY] 필수 설정이 누락된 상태로 서버가 실행되지 않도록 검증
@ConfigurationProperties(prefix = "jwt") // [NOTE] jwt 접두사의 외부 설정을 이 객체에 바인딩
public record JwtProperties(
        @NotBlank
        String issuer, // [NOTE] 토큰 발급자 식별값으로, 검증 시 신뢰하는 발급자인지 확인하는 데 사용

        @NotBlank
        String secret, // [CAUTION] 서명·검증에 사용하는 비밀 키로, 환경변수로 주입하고 로그에 출력하지 않음

        @NotNull
        Duration accessTokenTtl // [NOTE] Access Token의 발급 시각부터 만료까지의 유효기간
) {
    // [CAUTION] 설정 객체를 출력해도 서명 키가 노출되지 않도록 제외
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
