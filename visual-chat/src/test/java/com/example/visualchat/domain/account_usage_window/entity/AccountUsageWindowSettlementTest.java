package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 성공 정산과 실패 해제 시 다른 요청의 예약량 및 잔액 불변식을 검증 */
class AccountUsageWindowSettlementTest {

    @Test
    @DisplayName("성공한 요청의 예약만 해제하고 실제 비용을 더하며 다른 예약은 보존한다")
    void settlesOwnReservationOnly() {
        AccountUsageWindow window = reservedWindow();
        window.settleVie(new BigDecimal("200"), new BigDecimal("123.456789"));
        assertThat(window.getUsedVie()).isEqualByComparingTo("123.456789");
        assertThat(window.getReservedVie()).isEqualByComparingTo("300");
        assertThat(window.getRemainingVie()).isEqualByComparingTo("19576.543211");
        assertThat(window.getAllowanceVie()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("확정 실패는 해당 예약량만 해제하고 과거 사용량과 다른 예약은 유지한다")
    void releasesWithoutChargingOrResettingPastUsage() {
        AccountUsageWindow window = reservedWindow();
        window.settleVie(new BigDecimal("200"), new BigDecimal("100"));
        window.releaseVie(new BigDecimal("50"));
        assertThat(window.getUsedVie()).isEqualByComparingTo("100");
        assertThat(window.getReservedVie()).isEqualByComparingTo("250");
        assertThat(window.getRemainingVie()).isEqualByComparingTo("19650");
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0.000001,0.000001", "200.0000000,123.456789000", "500,500"})
    @DisplayName("0·최소 단위·정확한 예약 전액 및 불필요한 소수 0을 값 손실 없이 정산한다")
    void acceptsExactBoundaries(String reservation, String charge) {
        AccountUsageWindow window = reservedWindow();
        window.settleVie(new BigDecimal(reservation), new BigDecimal(charge));
        assertThat(window.getUsedVie()).isEqualByComparingTo(charge);
        assertThat(window.getReservedVie()).isEqualByComparingTo(new BigDecimal("500").subtract(new BigDecimal(reservation)));
        assertThat(window.getUsedVie().add(window.getReservedVie())).isLessThanOrEqualTo(window.getAllowanceVie());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "0.0000001", "1000000000000"})
    @DisplayName("잘못된 예약량은 정산과 해제 모두에서 기존 잔액을 바꾸지 않는다")
    void rejectsInvalidReservationWithoutMutation(String value) {
        AccountUsageWindow window = reservedWindow();
        BigDecimal invalid = value == null ? null : new BigDecimal(value);
        assertUnchanged(window, () -> window.settleVie(invalid, BigDecimal.ZERO));
        assertUnchanged(window, () -> window.releaseVie(invalid));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "0.0000001", "1000000000000", "200.000001"})
    @DisplayName("잘못된 차감량과 해당 요청 예약 초과 차감은 다른 예약으로 충당하지 않는다")
    void rejectsInvalidOrOverReservedCharge(String value) {
        AccountUsageWindow window = reservedWindow();
        BigDecimal charge = value == null ? null : new BigDecimal(value);
        assertUnchanged(window, () -> window.settleVie(new BigDecimal("200"), charge));
    }

    @Test
    @DisplayName("활성 예약 총량보다 큰 해제·정산은 거절한다")
    void rejectsExcessiveRelease() {
        AccountUsageWindow window = reservedWindow();
        assertUnchanged(window, () -> window.releaseVie(new BigDecimal("500.000001")));
        assertUnchanged(window, () -> window.settleVie(new BigDecimal("500.000001"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("numeric 저장 범위를 넘는 새 예약은 DB 접근 전에 거절한다")
    void rejectsStorageOverflowBeforeSaving() {
        AccountUsageWindow window = reservedWindow();
        assertUnchanged(window, () -> window.reserveVie(new BigDecimal("1000000000000")));
    }

    private AccountUsageWindow reservedWindow() {
        AccountUsageWindow window = AccountUsageWindow.of(User.of("owner", "owner@example.com", "encoded-password"));
        window.reserveVie(new BigDecimal("200"));
        window.reserveVie(new BigDecimal("300"));
        return window;
    }

    private void assertUnchanged(AccountUsageWindow window, Runnable operation) {
        List<BigDecimal> before = List.of(window.getUsedVie(), window.getReservedVie(), window.getRemainingVie());
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(List.of(window.getUsedVie(), window.getReservedVie(), window.getRemainingVie())).isEqualTo(before);
    }
}
