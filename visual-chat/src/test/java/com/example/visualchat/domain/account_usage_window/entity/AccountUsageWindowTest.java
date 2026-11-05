package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AccountUsageWindowTest {

    @Test
    @DisplayName("가입 완료 시각부터 30일인 첫 주기를 만들고 20,000 VIE를 제공한다")
    void createsInitialWindowFromSignupCompletion() {
        User user = user();

        AccountUsageWindow window = AccountUsageWindow.of(user);

        assertThat(window.getId()).isNull();
        assertThat(window.getUser()).isSameAs(user);
        assertThat(window.getPeriodStartAt()).isEqualTo(user.getSignupCompletedAt());
        assertThat(window.getPeriodEndAt()).isEqualTo(user.getSignupCompletedAt().plus(30, ChronoUnit.DAYS));
        assertThat(Duration.between(window.getPeriodStartAt(), window.getPeriodEndAt())).isEqualTo(Duration.ofDays(30));
        assertThat(window.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(window.getUsedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(window.getReservedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(window.getRemainingVie()).isEqualByComparingTo("20000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000001", "0.25", "20000", "20000.0000000"})
    @DisplayName("0과 최소 소수 단위, 잔여량 전체를 예약해도 제공량과 확정 사용량은 유지한다")
    void reservesAllowedAmountsWithoutChangingAllowanceOrUsage(String value) {
        AccountUsageWindow window = AccountUsageWindow.of(user());
        BigDecimal amount = new BigDecimal(value);

        window.reserveVie(amount);

        assertThat(window.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(window.getUsedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(window.getReservedVie()).isEqualByComparingTo(amount);
        assertThat(window.getRemainingVie()).isEqualByComparingTo(new BigDecimal("20000").subtract(amount));
    }

    @Test
    @DisplayName("여러 요청의 소수 예약량을 정확히 누적한다")
    void accumulatesDecimalReservationsExactly() {
        AccountUsageWindow window = AccountUsageWindow.of(user());

        window.reserveVie(new BigDecimal("0.1"));
        window.reserveVie(new BigDecimal("0.2"));

        assertThat(window.getReservedVie()).isEqualByComparingTo("0.3");
        assertThat(window.getRemainingVie()).isEqualByComparingTo("19999.7");
        assertThat(window.getAllowanceVie()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("예약 후 남은 VIE를 정확히 모두 예약할 수 있고 이후 양수 예약은 거절한다")
    void permitsExactRemainingAmountAndRejectsFurtherReservation() {
        AccountUsageWindow window = AccountUsageWindow.of(user());
        window.reserveVie(new BigDecimal("0.000001"));
        window.reserveVie(new BigDecimal("19999.999999"));
        window.reserveVie(BigDecimal.ZERO);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> window.reserveVie(new BigDecimal("0.000001")))
                .withMessage("예약 가능한 VIE가 부족합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(window.getReservedVie()).isEqualByComparingTo("20000");
        assertThat(window.getRemainingVie()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "-0.000001"})
    @DisplayName("null이나 음수 예약은 공통 오류로 거절하고 기존 예약을 보존한다")
    void rejectsMissingOrNegativeAmountWithoutMutation(String value) {
        AccountUsageWindow window = windowWithReservation();
        BigDecimal amount = value == null ? null : new BigDecimal(value);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> window.reserveVie(amount))
                .withMessage("예약량은 0 이상이어야 합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        assertOriginalBalances(window);
    }

    @ParameterizedTest
    @ValueSource(strings = {"19800.000001", "20000", "1000000000000"})
    @DisplayName("기존 예약을 제외한 잔여량보다 큰 예약은 상태 변경 없이 거절한다")
    void rejectsAmountAboveRemainingBalance(String value) {
        AccountUsageWindow window = windowWithReservation();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> window.reserveVie(new BigDecimal(value)))
                .withMessage("예약 가능한 VIE가 부족합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        assertOriginalBalances(window);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0000001", "0.0000005", "1.1234567", "19799.9999999"})
    @DisplayName("소수점 6자리로 정확히 저장할 수 없는 예약량은 반올림하지 않고 거절한다")
    void rejectsLossyDecimalPrecisionWithoutMutation(String value) {
        AccountUsageWindow window = windowWithReservation();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> window.reserveVie(new BigDecimal(value)))
                .withMessage("예약량은 소수점 6자리까지 입력할 수 있습니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        assertOriginalBalances(window);
    }

    @Test
    @DisplayName("소수점 뒤에 불필요한 0이 더 있어도 값 손실 없이 저장할 수 있으면 예약한다")
    void acceptsExtraTrailingZerosWithoutRounding() {
        AccountUsageWindow window = AccountUsageWindow.of(user());

        window.reserveVie(new BigDecimal("1.123456000"));

        assertThat(window.getReservedVie()).isEqualByComparingTo("1.123456");
        assertThat(window.getRemainingVie()).isEqualByComparingTo("19998.876544");
    }

    private AccountUsageWindow windowWithReservation() {
        AccountUsageWindow window = AccountUsageWindow.of(user());
        window.reserveVie(new BigDecimal("200"));
        return window;
    }

    private void assertOriginalBalances(AccountUsageWindow window) {
        assertThat(window.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(window.getUsedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(window.getReservedVie()).isEqualByComparingTo("200");
        assertThat(window.getRemainingVie()).isEqualByComparingTo("19800");
    }

    private User user() {
        return User.of("arvie", "user@example.com", "encoded-password");
    }
}
