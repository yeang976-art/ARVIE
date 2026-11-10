package com.example.visualchat.global.security.jwt;

import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** [ROLE] JWT 설정의 경계값과 허용 알고리즘·회원 식별 클레임 정책을 검증 */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @ParameterizedTest
    @ValueSource(longs = {-1000L, 0L, 999L})
    @DisplayName("토큰 유효기간이 1초 미만이면 키 구성을 거절한다")
    void rejectsTooShortLifetime(long millis) {
        var properties = new JwtProperties("arvie-test", JwtTestFixtures.SECRET, Duration.ofMillis(millis));
        assertThatIllegalArgumentException().isThrownBy(() -> config.jwtSecretKey(properties))
                .withMessage("Access Token의 유효기간은 1초 이상이어야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(longs = {1000L, 1_800_000L})
    @DisplayName("토큰 유효기간의 최소 경계와 일반 설정값을 허용한다")
    void acceptsSupportedLifetime(long millis) {
        var properties = new JwtProperties("arvie-test", JwtTestFixtures.SECRET, Duration.ofMillis(millis));
        assertThat(config.jwtSecretKey(properties).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    @DisplayName("잘못된 Base64 키는 입력값을 노출하지 않고 거절한다")
    void rejectsInvalidBase64WithoutLeakingInput() {
        String invalidSecret = "not-base64!private-input";
        var properties = new JwtProperties("arvie-test", invalidSecret, Duration.ofMinutes(30));
        assertThatIllegalArgumentException().isThrownBy(() -> config.jwtSecretKey(properties))
                .withMessage("JWT 서명 키는 올바른 Base64 형식이어야 합니다.")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(invalidSecret));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31})
    @DisplayName("디코딩한 키가 32바이트 미만이면 거절한다")
    void rejectsInsufficientKeyLength(int length) {
        var properties = new JwtProperties("arvie-test", Base64.getEncoder().encodeToString(new byte[length]),
                Duration.ofMinutes(30));
        assertThatIllegalArgumentException().isThrownBy(() -> config.jwtSecretKey(properties))
                .withMessage("JWT 서명 키는 Base64 디코딩 후 32바이트 이상이어야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 64})
    @DisplayName("32바이트 이상의 키를 원본 바이트 손실 없이 구성한다")
    void acceptsSupportedKeyLength(int length) {
        var properties = new JwtProperties("arvie-test", Base64.getEncoder().encodeToString(new byte[length]),
                Duration.ofMinutes(30));
        assertThat(config.jwtSecretKey(properties).getEncoded()).hasSize(length);
    }

    @Test
    @DisplayName("충분히 긴 동일 키로 서명했어도 HS512 토큰은 검증하지 않는다")
    void rejectsUnexpectedSigningAlgorithm() {
        var properties = new JwtProperties(JwtTestFixtures.ISSUER,
                Base64.getEncoder().encodeToString(new byte[64]), Duration.ofMinutes(30));
        var key = config.jwtSecretKey(properties);
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS512).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS512).type("JWT").build(),
                JwtTestFixtures.claims().build())).getTokenValue();
        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> config.jwtDecoder(key, properties).decode(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("회원 식별값이 빈 문자열이거나 공백인 토큰은 거절한다")
    void rejectsBlankSubject(String subject) {
        JwtProperties properties = JwtTestFixtures.properties();
        var key = config.jwtSecretKey(properties);
        String token = JwtTestFixtures.sign(config.jwtEncoder(key), JwtTestFixtures.claims().subject(subject).build());
        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> config.jwtDecoder(key, properties).decode(token));
    }
}
