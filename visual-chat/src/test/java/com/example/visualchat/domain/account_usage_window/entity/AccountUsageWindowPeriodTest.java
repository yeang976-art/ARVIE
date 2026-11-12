package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 새 이용 주기의 초기 수량과 가입 기산 불변식·이전 주기 보존을 검증 */
class AccountUsageWindowPeriodTest {
    private static final Instant SIGNUP = Instant.parse("2026-01-31T12:34:56.123456Z");

    @Test
    @DisplayName("현재 주기를 새로 만들어도 이전 주기의 예약량은 보존하고 새 주기에 20000 VIE를 제공한다")
    void createsNewWindowWithoutResettingPreviousReservation() {
        User user = user();
        AccountUsageWindow previous = AccountUsageWindow.of(user);
        previous.reserveVie(new BigDecimal("123.456789"));
        UsagePeriod period = UsagePeriod.from(SIGNUP, SIGNUP.plus(Duration.ofDays(95)));

        AccountUsageWindow current = AccountUsageWindow.of(user, period);

        assertThat(current.getUser()).isSameAs(user);
        assertThat(current.getPeriodStartAt()).isEqualTo(period.startAt());
        assertThat(current.getPeriodEndAt()).isEqualTo(period.endAt());
        assertThat(current.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(current.getUsedVie()).isEqualByComparingTo("0");
        assertThat(current.getReservedVie()).isEqualByComparingTo("0");
        assertThat(previous.getReservedVie()).isEqualByComparingTo("123.456789");
        assertThat(previous.getPeriodStartAt()).isEqualTo(SIGNUP);
    }

    @ParameterizedTest
    @MethodSource("invalidPeriods")
    @DisplayName("누락되거나 가입 기준의 30일 구간에서 벗어난 기간은 생성할 수 없다")
    void rejectsInvalidPeriod(UsagePeriod period) {
        assertServerError(() -> AccountUsageWindow.of(user(), period));
    }

    static Stream<Arguments> invalidPeriods() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new UsagePeriod(null, SIGNUP.plus(Duration.ofDays(30)))),
                Arguments.of(new UsagePeriod(SIGNUP, null)),
                Arguments.of(new UsagePeriod(SIGNUP.plusNanos(1), SIGNUP.plus(Duration.ofDays(30)).plusNanos(1))),
                Arguments.of(new UsagePeriod(SIGNUP, SIGNUP.plus(Duration.ofDays(29)))),
                Arguments.of(new UsagePeriod(SIGNUP.minus(Duration.ofDays(30)), SIGNUP))
        );
    }

    @Test
    @DisplayName("회원 없이 이용 주기를 생성할 수 없다")
    void rejectsMissingOwner() {
        assertServerError(() -> AccountUsageWindow.of(null, UsagePeriod.from(SIGNUP, SIGNUP)));
    }

    private void assertServerError(Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR));
    }

    private User user() {
        User user = User.of("tester", "tester@example.com", "encoded-test-value");
        ReflectionTestUtils.setField(user, "signupCompletedAt", SIGNUP);
        return user;
    }
}
