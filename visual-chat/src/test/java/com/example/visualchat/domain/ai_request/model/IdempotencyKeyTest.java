package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 멱등키의 허용 문자·길이 경계와 원문 보존 규칙을 검증 */
class IdempotencyKeyTest {
    @ParameterizedTest
    @ValueSource(strings = {"a", "A_0-z", "9b6e1c47-6e4a-4bcc-8f96-b25cbf260533"})
    @DisplayName("영문·숫자·밑줄·하이픈과 UUID 형태의 키를 그대로 보존한다")
    void preservesValidKey(String value) {
        assertThat(new IdempotencyKey(value).value()).isEqualTo(value);
    }

    @Test
    @DisplayName("최대 128자는 허용하고 129자는 거절한다")
    void validatesLengthBoundary() {
        assertThat(new IdempotencyKey("a".repeat(128)).value()).hasSize(128);
        assertInvalid("a".repeat(129));
    }

    @Test
    @DisplayName("대소문자가 다른 키는 서로 다른 의도적 요청으로 구분한다")
    void keepsKeysCaseSensitive() {
        assertThat(new IdempotencyKey("Case-Key")).isNotEqualTo(new IdempotencyKey("case-key"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " key", "key ", "a b", "a\nb", "a\tb", "한글", "a:b", "a/b", "🌸"})
    @DisplayName("누락·공백·비허용 문자를 임의로 정규화하지 않고 거절한다")
    void rejectsInvalidFormat(String value) {
        assertInvalid(value);
    }

    private void assertInvalid(String value) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(() -> new IdempotencyKey(value))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
