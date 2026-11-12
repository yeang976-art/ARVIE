package com.example.visualchat.domain.ai_request.util;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 원문을 보관하지 않는 SHA-256 지문의 재현성과 내용 구분을 검증 */
class AiRequestFingerprintGeneratorTest {
    private final AiRequestFingerprintGenerator generator = new AiRequestFingerprintGenerator();

    @Test
    @DisplayName("한글·이모지·줄바꿈을 포함한 UTF-8 직렬화 규칙을 고정된 지문으로 검증한다")
    void preservesPublishedFingerprintFormat() {
        assertThat(generator.generate(7, "안녕🌸\n 레브"))
                .isEqualTo("358f59eba3205ab4ece703cd3e690e07a00f38362d6bd221f57584be98be9c7e");
    }

    @Test
    @DisplayName("같은 내용의 반복 생성은 같은 64자리 지문이며 원문을 포함하지 않는다")
    void returnsStableDigestWithoutOriginalContent() {
        String first = generator.generate(7, "공개하지 않을 사용자 원문");
        assertThat(first).matches("[0-9a-f]{64}")
                .isEqualTo(generator.generate(7, "공개하지 않을 사용자 원문"))
                .doesNotContain("공개하지 않을 사용자 원문");
    }

    @Test
    @DisplayName("같은 메시지라도 채팅방이 다르면 다른 요청 지문이다")
    void includesChatRoomIdentity() {
        assertThat(generator.generate(7, "안녕")).isNotEqualTo(generator.generate(8, "안녕"));
    }

    @ParameterizedTest
    @ValueSource(strings = {" 안녕", "안녕 ", "안녕\n", "안녕!", "안\n녕"})
    @DisplayName("공백·줄바꿈·문장 변경을 지문 생성 전에 정규화하지 않는다")
    void distinguishesExactContent(String content) {
        assertThat(generator.generate(7, content)).isNotEqualTo(generator.generate(7, "안녕"));
    }

    @Test
    @DisplayName("문자열 연결의 필드 경계가 다른 요청을 같은 입력으로 만들지 않는다")
    void keepsFieldBoundariesUnambiguous() {
        assertThat(generator.generate(1, "23\n메시지")).isNotEqualTo(generator.generate(12, "3\n메시지"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\n\t"})
    @DisplayName("본문 누락이나 공백 본문은 지문 생성 전에 거절한다")
    void rejectsMissingContent(String content) {
        assertInvalid(() -> generator.generate(7, content));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    @DisplayName("유효하지 않은 채팅방 식별자는 지문에 포함하지 않는다")
    void rejectsInvalidRoom(long roomId) {
        assertInvalid(() -> generator.generate(roomId, "안녕"));
    }

    private void assertInvalid(Runnable operation) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
