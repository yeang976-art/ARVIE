package com.example.visualchat.global.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;

/** [ROLE] JWT 설정을 검증하고 서명 키·발급기·검증기·시간 제공 빈을 구성 */
@Configuration
// [NOTE] JwtProperties를 빈으로 등록하고 jwt 접두사의 설정 바인딩·검증을 활성화
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    // [TOPIC] 유효기간·서명 키 검증과 키 객체 구성
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

    // [TOPIC] HS256 토큰 발급기와 검증기 등록
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        // [NOTE] 전달받은 클레임으로 JWT를 만들고 비밀 키로 서명
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties) {
        // [WHY] 토큰이 지정한 알고리즘 외의 방식으로 검증되지 않도록 HS256으로 제한
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // [WHY] 기본 변환기가 누락된 iat를 보완하기 전에 원본 발급 시각의 존재 여부를 검증
        MappedJwtClaimSetConverter claimConverter = MappedJwtClaimSetConverter
                .withDefaults(Collections.emptyMap());

        decoder.setClaimSetConverter(claims -> {
            if (claims.get("iat") == null) {
                throw new BadJwtException("토큰에 발급 시각이 필요합니다.");
            }
            return claimConverter.convert(claims);
        });

        // [NOTE] 기본 시간 검증에 발급자 및 필수 클레임 검증을 추가
        decoder.setJwtValidator(
                JwtValidators.createDefaultWithValidators(
                        new JwtIssuerValidator(properties.issuer()),

                        // [WHY] 만료 시각이 없는 토큰이 계속 사용되는 것을 방지
                        new JwtClaimValidator<Instant>("exp", obj1 -> true),

                        // [NOTE] 발급 시각은 필수값으로 사용
                        new JwtClaimValidator<Instant>("iat", obj -> true),

                        // [NOTE] sub에 저장할 회원 식별값의 누락·공백을 차단
                        new JwtClaimValidator<String>("sub", subject -> !subject.isBlank())
                )
        );

        return decoder;
    }

    // [TOPIC] 토큰 발급 시각을 제공할 공통 시계 등록
    // [WHY] 테스트에서 고정된 시간을 주입해 발급·만료 시각을 정확하게 검증
    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }
}
