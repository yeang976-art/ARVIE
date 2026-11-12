package com.example.visualchat.domain.account_usage_window.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** [ROLE] 한 번 읽은 시각의 주기 선택과 동시 생성 충돌 복구 범위를 검증 */
class AccountUsageWindowCommandTest {
    private static final Instant SIGNUP = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final Instant NOW = SIGNUP.plus(Duration.ofDays(95));
    private final UserService users = mock(UserService.class);
    private final AccountUsageWindowService windows = mock(AccountUsageWindowService.class);
    private final Clock clock = mock(Clock.class);
    private final AccountUsageWindowCommand command = new AccountUsageWindowCommand(users, windows, clock);
    private final User user = User.of("tester", "tester@example.com", "encoded-test-value");
    private final UsagePeriod period = UsagePeriod.from(SIGNUP, NOW);
    private final UsageResponse response = new UsageResponse(101L, new BigDecimal("20000"),
            new BigDecimal("123.5"), new BigDecimal("456.25"), new BigDecimal("19420.25"), period.startAt(), period.endAt());

    @BeforeEach
    void prepareMember() {
        ReflectionTestUtils.setField(user, "signupCompletedAt", SIGNUP);
        when(users.getById(41L)).thenReturn(user);
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    @DisplayName("현재 주기가 있으면 기존 사용량과 예약량을 그대로 반환하고 새로 생성하지 않는다")
    void returnsExistingUsage() {
        when(windows.findByPeriod(41L, period.startAt())).thenReturn(Optional.of(response));
        assertThat(command.getCurrentUsage(41L)).isEqualTo(response);
        verify(windows, never()).create(any(), any());
        verify(clock, times(1)).instant();
    }

    @Test
    @DisplayName("장기 미접속 후 현재 주기가 없으면 가입 기산의 현재 구간만 생성한다")
    void createsOnlyCurrentPeriod() {
        when(windows.findByPeriod(41L, period.startAt())).thenReturn(Optional.empty());
        when(windows.create(user, period)).thenReturn(response);
        assertThat(command.getCurrentUsage(41L)).isEqualTo(response);
        verify(windows).create(user, period);
    }

    @Test
    @DisplayName("처리 중 시계가 다음 주기로 넘어가도 처음 읽은 시각의 기간으로 생성한다")
    void readsClockOnlyOnceAtBoundary() {
        Instant boundary = SIGNUP.plus(Duration.ofDays(30));
        when(clock.instant()).thenReturn(boundary.minusNanos(1)).thenReturn(boundary);
        UsagePeriod captured = UsagePeriod.from(SIGNUP, boundary.minusNanos(1));
        when(windows.findByPeriod(41L, captured.startAt())).thenReturn(Optional.empty());
        when(windows.create(user, captured)).thenReturn(response);

        assertThat(command.getCurrentUsage(41L)).isEqualTo(response);
        verify(clock, times(1)).instant();
        verify(windows).create(user, captured);
    }

    @Test
    @DisplayName("회원이 없으면 시각 조회나 이용 주기 생성을 진행하지 않는다")
    void rejectsMissingMember() {
        when(users.getById(41L)).thenThrow(new ServiceException(ErrorCode.USER_NOT_FOUND));
        assertThatExceptionOfType(ServiceException.class).isThrownBy(() -> command.getCurrentUsage(41L))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        verifyNoInteractions(windows, clock);
    }

    @Test
    @DisplayName("동일 회원·기간의 유일성 충돌만 먼저 저장된 주기 조회로 복구한다")
    void recoversDuplicatePeriod() {
        DataIntegrityViolationException failure = failure("23505", "uk_account_usage_windows_user_period_start");
        when(windows.findByPeriod(41L, period.startAt())).thenReturn(Optional.empty()).thenReturn(Optional.of(response));
        when(windows.create(user, period)).thenThrow(failure);

        assertThat(command.getCurrentUsage(41L)).isEqualTo(response);
        verify(windows, times(2)).findByPeriod(41L, period.startAt());
        verify(windows, times(1)).create(user, period);
        verify(clock, times(1)).instant();
    }

    @ParameterizedTest
    @CsvSource(value = {"23503,uk_account_usage_windows_user_period_start", "23505,another_constraint", "23505,<null>"}, nullValues = "<null>")
    @DisplayName("다른 SQL 상태나 제약의 오류는 기존 주기 조회로 복구하지 않는다")
    void propagatesUnrelatedIntegrityError(String state, String constraint) {
        assertUnrecoverable(failure(state, constraint));
    }

    @Test
    @DisplayName("DB 제약 정보를 알 수 없는 무결성 오류는 그대로 전달한다")
    void propagatesUnknownIntegrityError() {
        assertUnrecoverable(new DataIntegrityViolationException("테스트용 저장 오류"));
    }

    @Test
    @DisplayName("유일성 충돌 후에도 기존 주기가 없으면 서버 오류로 처리한다")
    void rejectsMissingRecoveryResult() {
        DataIntegrityViolationException failure = failure("23505", "uk_account_usage_windows_user_period_start");
        when(windows.findByPeriod(41L, period.startAt())).thenReturn(Optional.empty());
        when(windows.create(user, period)).thenThrow(failure);
        assertThatExceptionOfType(ServiceException.class).isThrownBy(() -> command.getCurrentUsage(41L))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR));
    }

    private void assertUnrecoverable(DataIntegrityViolationException failure) {
        when(windows.findByPeriod(41L, period.startAt())).thenReturn(Optional.empty());
        when(windows.create(user, period)).thenThrow(failure);
        assertThatThrownBy(() -> command.getCurrentUsage(41L)).isSameAs(failure);
        verify(windows, times(1)).findByPeriod(41L, period.startAt());
    }

    private DataIntegrityViolationException failure(String state, String constraint) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getSQLState()).thenReturn(state);
        when(violation.getConstraintName()).thenReturn(constraint);
        return new DataIntegrityViolationException("테스트용 저장 오류", new IllegalStateException(violation));
    }
}
