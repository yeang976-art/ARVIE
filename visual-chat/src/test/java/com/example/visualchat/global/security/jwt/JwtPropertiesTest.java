package com.example.visualchat.global.security.jwt;

import com.example.visualchat.domain.auth.dto.LoginResponse;
import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.support.JwtTestFixtures;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** [ROLE] 외부 JWT 설정의 필수값 검증과 인증 정보 모델의 문자열 노출 방지를 검증 */
class JwtPropertiesTest {

    private static ValidatorFactory validatorFactory;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @MethodSource("invalidProperties")
    @DisplayName("JWT 발급자·서명 키·유효기간의 필수값 누락을 검출한다")
    void rejectsMissingProperties(JwtProperties properties, String field) {
        assertThat(validatorFactory.getValidator().validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString()).contains(field);
    }

    static Stream<Arguments> invalidProperties() {
        Duration ttl = Duration.ofMinutes(30);
        return Stream.of(
                Arguments.of(new JwtProperties(null, JwtTestFixtures.SECRET, ttl), "issuer"),
                Arguments.of(new JwtProperties(" ", JwtTestFixtures.SECRET, ttl), "issuer"),
                Arguments.of(new JwtProperties("arvie-test", null, ttl), "secret"),
                Arguments.of(new JwtProperties("arvie-test", " ", ttl), "secret"),
                Arguments.of(new JwtProperties("arvie-test", JwtTestFixtures.SECRET, null), "accessTokenTtl")
        );
    }

    @Test
    @DisplayName("정상적인 필수 설정은 검증에 성공한다")
    void acceptsCompleteProperties() {
        assertThat(validatorFactory.getValidator().validate(JwtTestFixtures.properties())).isEmpty();
    }

    @Test
    @DisplayName("설정·발급 결과·로그인 응답을 문자열로 출력해도 키와 토큰은 노출되지 않는다")
    void redactsSecretsFromStringRepresentations() {
        Instant expiry = Instant.parse("2027-01-01T00:30:00Z");
        IssuedToken issued = new IssuedToken("sensitive-access-token", expiry);
        assertThat(JwtTestFixtures.properties().toString()).doesNotContain(JwtTestFixtures.SECRET);
        assertThat(issued.toString()).contains(expiry.toString()).doesNotContain(issued.token());
        assertThat(LoginResponse.from(issued).toString()).contains(expiry.toString()).doesNotContain(issued.token());
    }
}
