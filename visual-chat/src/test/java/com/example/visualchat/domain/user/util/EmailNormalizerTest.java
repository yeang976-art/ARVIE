package com.example.visualchat.domain.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** [ROLE] 이메일 도메인 정규화와 로컬 부분 보존 규칙을 검증 */
class EmailNormalizerTest {

    @ParameterizedTest(name = "이메일 정규화 사례 {index}")
    @CsvSource(value = {
            "User@EXAMPLE.COM, User@example.com",
            "User@I.EXAMPLE, User@i.example",
            "\"quoted@Local\"@EXAMPLE.COM, \"quoted@Local\"@example.com",
            "user@example.com, user@example.com",
            "not-an-email, not-an-email",
            "<null>, <null>"
    }, nullValues = "<null>")
    @DisplayName("도메인만 소문자로 바꾸고 로컬 부분과 검증 전 입력은 보존한다")
    void normalizesOnlyDomain(String input, String expected) {
        assertThat(EmailNormalizer.normalizeDomain(input)).isEqualTo(expected);
    }
}