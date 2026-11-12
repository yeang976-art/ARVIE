package com.example.visualchat.domain.account_usage_window.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 가입 기산의 고정 30일 구간과 나노초 경계·장기 미접속 계산을 검증 */
class UsagePeriodTest {
    private static final Instant SIGNUP = Instant.parse("2026-01-31T12:34:56.123456789Z");

    @ParameterizedTest(name = "주기 경계 사례 {index}")
    @MethodSource("boundaries")
    @DisplayName("시작은 포함하고 종료 시각부터 다음 30일 구간을 선택한다")
    void selectsSignupAnchoredPeriod(Duration elapsed, long expectedIndex) {
        Instant now = SIGNUP.plus(elapsed);
        UsagePeriod period = UsagePeriod.from(SIGNUP, now);

        assertThat(period.startAt()).isEqualTo(SIGNUP.plus(Duration.ofDays(expectedIndex * 30)));
        assertThat(Duration.between(period.startAt(), period.endAt())).isEqualTo(Duration.ofDays(30));
        assertThat(period.startAt()).isBeforeOrEqualTo(now);
        assertThat(period.endAt()).isAfter(now);
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of(Duration.ZERO, 0L),
                Arguments.of(Duration.ofDays(30).minusNanos(1), 0L),
                Arguments.of(Duration.ofDays(30), 1L),
                Arguments.of(Duration.ofDays(30).plusNanos(1), 1L),
                Arguments.of(Duration.ofDays(60).minusNanos(1), 1L),
                Arguments.of(Duration.ofDays(60), 2L),
                Arguments.of(Duration.ofDays(95), 3L),
                Arguments.of(Duration.ofDays(3650).plusHours(3), 121L)
        );
    }

    @Test
    @DisplayName("1월 31일 가입의 첫 주기는 달력 한 달 대신 정확한 30일 뒤에 끝난다")
    void usesFixedDaysInsteadOfCalendarMonth() {
        assertThat(UsagePeriod.from(SIGNUP, SIGNUP).endAt())
                .isEqualTo(Instant.parse("2026-03-02T12:34:56.123456789Z"));
    }

    @ParameterizedTest
    @MethodSource("invalidTimes")
    @DisplayName("누락된 시각과 가입 이전 현재 시각은 내부 오류로 거절한다")
    void rejectsInvalidServerTimes(Instant signup, Instant now) {
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> UsagePeriod.from(signup, now))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR));
    }

    static Stream<Arguments> invalidTimes() {
        return Stream.of(Arguments.of(null, SIGNUP), Arguments.of(SIGNUP, null),
                Arguments.of(SIGNUP, SIGNUP.minusNanos(1)));
    }
}
