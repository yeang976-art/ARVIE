package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 과금 입력의 분류 합계와 음수·정수 경계를 검증 */
class AiTokenUsageTest {

    @Test
    @DisplayName("캐시 읽기와 두 쓰기 유형을 제외한 입력만 일반 입력으로 분류한다")
    void separatesOrdinaryInputWithoutDoubleCounting() {
        AiTokenUsage usage = new AiTokenUsage(12000, 4000, 3000, 4000, 1000);
        assertThat(usage.ordinaryInputTokens()).isEqualTo(1000);
        assertThat(usage.cachedInputTokens()).isEqualTo(4000);
        assertThat(usage.cacheWrite30mTokens()).isEqualTo(3000);
        assertThat(usage.cacheWrite12hTokens()).isEqualTo(4000);
        assertThat(usage.outputTokens()).isEqualTo(1000);
    }

    @Test
    @DisplayName("실제 사용량이 모두 0이면 임의의 최소 사용량을 추가하지 않는다")
    void acceptsZeroUsage() {
        assertThat(new AiTokenUsage(0, 0, 0, 0, 0).ordinaryInputTokens()).isZero();
    }

    @Test
    @DisplayName("입력 전체가 캐시 유형에 배정되면 일반 입력은 0이다")
    void acceptsExactPartition() {
        assertThat(new AiTokenUsage(6, 1, 2, 3, 0).ordinaryInputTokens()).isZero();
    }

    @Test
    @DisplayName("long 최댓값의 유효한 분류도 합산 오버플로 없이 검증한다")
    void acceptsLargestRepresentablePartition() {
        AiTokenUsage usage = new AiTokenUsage(Long.MAX_VALUE, Long.MAX_VALUE - 2, 1, 1, Long.MAX_VALUE);
        assertThat(usage.ordinaryInputTokens()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidUsage")
    @DisplayName("음수 또는 입력보다 큰 캐시 분류는 오버플로로 우회할 수 없다")
    void rejectsInvalidCounts(long input, long cached, long write30m, long write12h, long output) {
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> new AiTokenUsage(input, cached, write30m, write12h, output))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    static Stream<Arguments> invalidUsage() {
        return Stream.of(
                Arguments.of(-1L, 0L, 0L, 0L, 0L),
                Arguments.of(1L, -1L, 0L, 0L, 0L),
                Arguments.of(1L, 0L, -1L, 0L, 0L),
                Arguments.of(1L, 0L, 0L, -1L, 0L),
                Arguments.of(1L, 0L, 0L, 0L, -1L),
                Arguments.of(1L, 2L, 0L, 0L, 0L),
                Arguments.of(1L, 0L, 2L, 0L, 0L),
                Arguments.of(1L, 0L, 0L, 2L, 0L),
                Arguments.of(5L, 2L, 2L, 2L, 0L),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, 1L, 0L, 0L),
                Arguments.of(Long.MAX_VALUE, 0L, Long.MAX_VALUE, 1L, 0L),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0L)
        );
    }
}
