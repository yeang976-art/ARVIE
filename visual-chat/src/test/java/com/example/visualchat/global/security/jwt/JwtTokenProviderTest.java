package com.example.visualchat.global.security.jwt;

import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** [ROLE] 고정 시계로 회원 식별값·발급 클레임·응답 만료 시각을 검증 */
class JwtTokenProviderTest {

    @Test
    @DisplayName("회원 ID와 설정된 유효기간으로 검증 가능한 HS256 토큰을 발급한다")
    void issuesTokenWithMemberAndConsistentExpiry() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
        var properties = new JwtProperties(JwtTestFixtures.ISSUER,
                JwtTestFixtures.SECRET, Duration.ofMinutes(30).plusMillis(500));
        JwtConfig config = new JwtConfig();
        var key = config.jwtSecretKey(properties);
        var provider = new JwtTokenProvider(config.jwtEncoder(key), properties, Clock.fixed(now, ZoneOffset.UTC));

        IssuedToken issued = provider.issue(41L);
        Jwt decoded = config.jwtDecoder(key, properties).decode(issued.token());

        assertThat(decoded.getSubject()).isEqualTo("41");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtTestFixtures.ISSUER);
        assertThat(decoded.getIssuedAt()).isEqualTo(now.truncatedTo(ChronoUnit.SECONDS));
        assertThat(decoded.getExpiresAt()).isEqualTo(issued.expiresAt())
                .isEqualTo(now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1800));
        assertThat(decoded.getHeaders()).containsEntry("alg", "HS256").containsEntry("typ", "JWT");
        assertThat(decoded.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("저장된 회원을 식별할 수 없는 ID로는 토큰을 발급하지 않는다")
    void rejectsInvalidMemberId(Long userId) {
        JwtConfig config = new JwtConfig();
        JwtProperties properties = JwtTestFixtures.properties();
        var provider = new JwtTokenProvider(config.jwtEncoder(config.jwtSecretKey(properties)),
                properties, Clock.systemUTC());
        assertThatIllegalArgumentException().isThrownBy(() -> provider.issue(userId))
                .withMessage("토큰을 발급할 회원 ID는 양수여야 합니다.");
    }
}
