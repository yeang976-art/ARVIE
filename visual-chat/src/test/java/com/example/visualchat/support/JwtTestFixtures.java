package com.example.visualchat.support;

import com.example.visualchat.global.security.jwt.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.time.Duration;
import java.time.Instant;

/** [ROLE] JWT 테스트의 설정과 서명 토큰 생성 데이터를 제공 */
public final class JwtTestFixtures {

    public static final String ISSUER = "arvie-test";
    // [CAUTION] 실제 서버에서 사용하지 않는 테스트 전용 32바이트 키
    public static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private JwtTestFixtures() {
    }

    public static JwtProperties properties() {
        return new JwtProperties(ISSUER, SECRET, Duration.ofMinutes(30));
    }

    public static JwtClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer(ISSUER).subject("41")
                .issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(600));
    }

    public static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
