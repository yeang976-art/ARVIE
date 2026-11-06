package com.example.visualchat.global.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.Base64;

@Configuration
// [NOTE] JwtProperties를 빈으로 등록하고 jwt 접두사의 설정 바인딩·검증을 활성화
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    // [WHY] 발급기와 검증기가 동일한 서명 키를 사용하도록 빈으로 등록
    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        // [WHY] @NotNull만으로는 기간의 범위를 검증할 수 없어 최소 1초인지 확인
        // [CAUTION] 잘못된 설정은 서버 시작 시 실패시켜 인증 기능이 불완전한 상태로 실행되는 것을 방지
        if (properties.accessTokenTtl().compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Access Token의 유효기간은 1초 이상이어야 합니다.");
        }

        byte[] keyBytes;

        try {
            // [NOTE] 설정에 저장된 Base64 문자열을 실제 서명에 사용할 원본 키 바이트로 복원
            // [CAUTION] Base64는 인코딩 방식이므로, 원본 키 자체를 안전한 난수로 생성해야 함
            keyBytes = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException e) {
            // [CAUTION] 설정 오류를 알리되 비밀 키나 입력 문자열은 오류 메시지에 포함하지 않음
            throw new IllegalArgumentException("JWT 서명 키는 올바른 Base64 형식이어야 합니다.");
        }

        // [WHY] HS256에 필요한 최소 256비트(32바이트)인지 디코딩한 원본 키 기준으로 검증
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT 서명 키는 Base64 디코딩 후 32바이트 이상이어야 합니다.");
        }

        // [NOTE] 검증한 키 바이트를 HmacSHA256 알고리즘에서 사용할 SecretKey 객체로 구성
        // [NOTE] 실제 JWT 서명과 검증은 이후 등록할 JwtEncoder와 JwtDecoder가 수행
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
