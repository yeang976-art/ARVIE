package com.example.visualchat.global.security.jwt;

import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider implements TokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock jwtClock;

    @Override
    public IssuedToken issue(Long userId) {
        // [WHY] 아직 저장되지 않았거나 유효하지 않은 회원 식별값으로 토큰이 생성되는 것을 방지
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("토큰을 발급할 회원 ID는 양수여야 합니다.");
        }

        // [NOTE] JWT에 기록할 시각과 반환할 만료 시각을 초 단위로 일치시킴
        Instant issuedAt = jwtClock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.accessTokenTtl()).truncatedTo(ChronoUnit.SECONDS);

        // [NOTE] 발급자·회원 식별값·발급 시각·만료 시각을 토큰에 포함
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        // [WHY] JwtConfig의 발급·검증 알고리즘과 동일하게 HS256을 명시
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        return new IssuedToken(jwt.getTokenValue(), expiresAt);
    }
}