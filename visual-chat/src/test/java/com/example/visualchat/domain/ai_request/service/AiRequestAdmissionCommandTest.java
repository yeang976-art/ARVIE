package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowCommand;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiRequestReceipt;
import com.example.visualchat.domain.ai_request.model.AiRequestSnapshot;
import com.example.visualchat.domain.ai_request.model.IdempotencyKey;
import com.example.visualchat.domain.ai_request.util.AiRequestFingerprintGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** [ROLE] 기존 요청 재사용과 지문 충돌·저장 실패의 복구 범위를 검증 */
class AiRequestAdmissionCommandTest {
    private static final long USER = 41L;
    private static final long ROOM = 73L;
    private static final long WINDOW = 89L;
    private static final String CONTENT = "사용자 원문";
    private static final IdempotencyKey KEY = new IdempotencyKey("request-key");
    private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID REQUEST = UUID.fromString("88888888-1111-4222-8333-444444444444");
    private final AiRequestService requests = mock(AiRequestService.class);
    private final AiRequestCommand reservation = mock(AiRequestCommand.class);
    private final AccountUsageWindowCommand windows = mock(AccountUsageWindowCommand.class);
    private final AiRequestFingerprintGenerator generator = new AiRequestFingerprintGenerator();
    private final AiRequestAdmissionCommand command = new AiRequestAdmissionCommand(requests, reservation, windows, generator);
    private String fingerprint;

    @BeforeEach
    void prepareFingerprint() {
        fingerprint = generator.generate(ROOM, CONTENT);
    }

    @ParameterizedTest
    @EnumSource(AiRequestStatus.class)
    @DisplayName("기존 처리 중·성공·실패 요청의 재전송은 새 예약 없이 같은 요청과 상태로 연결한다")
    void reusesEveryExistingStatus(AiRequestStatus status) {
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.of(snapshot(status, fingerprint)));
        AiRequestReceipt result = admit();
        assertThat(result.requestId()).isEqualTo(REQUEST);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.newlyCreated()).isFalse();
        verifyNoInteractions(windows, reservation);
    }

    @Test
    @DisplayName("같은 키에 다른 지문은 잔액 조회나 예약 없이 충돌로 거절한다")
    void rejectsDifferentFingerprintBeforeReservation() {
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.of(snapshot(AiRequestStatus.PROCESSING, "b".repeat(64))));
        assertError(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, this::admit);
        verifyNoInteractions(windows, reservation);
    }

    @Test
    @DisplayName("기존 요청이 없을 때만 서버가 선택한 주기에 예약하고 신규 접수로 반환한다")
    void createsOnlyWhenMissing() {
        prepareNewRequest();
        when(reservation.reserve(USER, ROOM, WINDOW, KEY.value(), fingerprint, 1000, 500, DEADLINE)).thenReturn(REQUEST);
        AiRequestReceipt result = admit();
        assertThat(result.requestId()).isEqualTo(REQUEST);
        assertThat(result.status()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(result.newlyCreated()).isTrue();
        var order = inOrder(requests, windows, reservation);
        order.verify(requests).findByKey(USER, KEY);
        order.verify(windows).getCurrentUsage(USER);
        order.verify(reservation).reserve(USER, ROOM, WINDOW, KEY.value(), fingerprint, 1000, 500, DEADLINE);
    }

    @Test
    @DisplayName("중첩된 멱등키 유일성 충돌은 예약 실패 이후 기존 요청을 다시 조회한다")
    void recoversExpectedUniqueConflict() {
        DataIntegrityViolationException failure = integrity("23505", "uk_ai_requests_user_idempotency_key");
        prepareNewRequest();
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(snapshot(AiRequestStatus.SUCCEEDED, fingerprint)));
        failReservation(failure);
        AiRequestReceipt result = admit();
        assertThat(result.requestId()).isEqualTo(REQUEST);
        assertThat(result.status()).isEqualTo(AiRequestStatus.SUCCEEDED);
        assertThat(result.newlyCreated()).isFalse();
        var order = inOrder(requests, reservation);
        order.verify(requests).findByKey(USER, KEY);
        order.verify(reservation).reserve(USER, ROOM, WINDOW, KEY.value(), fingerprint, 1000, 500, DEADLINE);
        order.verify(requests).findByKey(USER, KEY);
    }

    @Test
    @DisplayName("동시 접수의 승자가 다른 지문이면 충돌 복구에서도 409로 거절한다")
    void rejectsDifferentFingerprintAfterUniqueConflict() {
        DataIntegrityViolationException failure = integrity("23505", "uk_ai_requests_user_idempotency_key");
        prepareNewRequest();
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(snapshot(AiRequestStatus.PROCESSING, "c".repeat(64))));
        failReservation(failure);
        assertError(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, this::admit);
    }

    @ParameterizedTest
    @CsvSource(value = {"23503,uk_ai_requests_user_idempotency_key", "23505,another_constraint", "23505,<null>"}, nullValues = "<null>")
    @DisplayName("다른 SQL 상태·제약 오류는 기존 요청으로 위장해 복구하지 않는다")
    void propagatesUnrelatedIntegrityFailure(String state, String constraint) {
        DataIntegrityViolationException failure = integrity(state, constraint);
        prepareNewRequest();
        failReservation(failure);
        assertThatThrownBy(this::admit).isSameAs(failure);
        verify(requests, times(1)).findByKey(USER, KEY);
    }

    @Test
    @DisplayName("제약을 식별할 수 없는 무결성 오류는 그대로 전달한다")
    void propagatesUnknownIntegrityFailure() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("테스트용 DB 오류");
        prepareNewRequest();
        failReservation(failure);
        assertThatThrownBy(this::admit).isSameAs(failure);
        verify(requests, times(1)).findByKey(USER, KEY);
    }

    @Test
    @DisplayName("유일성 충돌 뒤 요청도 찾지 못하면 임의 재실행하지 않고 서버 오류를 반환한다")
    void rejectsMissingWinnerAfterUniqueConflict() {
        DataIntegrityViolationException failure = integrity("23505", "uk_ai_requests_user_idempotency_key");
        prepareNewRequest();
        failReservation(failure);
        assertError(ErrorCode.SERVER_ERROR, this::admit);
        verify(reservation, times(1)).reserve(any(), any(), any(), any(), any(), anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("잔액 또는 낙관적 락 오류 시 같은 키의 승자가 있으면 기존 요청으로 연결한다")
    void recoversConcurrentWinnerAfterBusinessOrVersionFailure(boolean optimistic) {
        RuntimeException failure = optimistic ? new OptimisticLockingFailureException("테스트용 버전 충돌")
                : new ServiceException(ErrorCode.INVALID_INPUT, "예약 가능한 VIE가 부족합니다.");
        prepareNewRequest();
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(snapshot(AiRequestStatus.PROCESSING, fingerprint)));
        failReservation(failure);
        assertThat(admit().newlyCreated()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("같은 키의 승자가 없다면 잔액·버전 오류를 원래 예외 그대로 전달한다")
    void propagatesFailureWhenNoConcurrentWinnerExists(boolean optimistic) {
        RuntimeException failure = optimistic ? new OptimisticLockingFailureException("테스트용 버전 충돌")
                : new ServiceException(ErrorCode.USAGE_PERIOD_CHANGED);
        prepareNewRequest();
        failReservation(failure);
        assertThatThrownBy(this::admit).isSameAs(failure);
    }

    @Test
    @DisplayName("잘못된 키나 공백 본문은 DB 접근 전에 거절한다")
    void rejectsInvalidIdentityBeforeDatabaseAccess() {
        assertError(ErrorCode.INVALID_INPUT, () -> command.admit(USER, ROOM, "bad key", CONTENT, 1000, 500, DEADLINE));
        assertError(ErrorCode.INVALID_INPUT, () -> command.admit(USER, ROOM, KEY.value(), " ", 1000, 500, DEADLINE));
        verifyNoInteractions(requests, windows, reservation);
    }

    private AiRequestReceipt admit() {
        return command.admit(USER, ROOM, KEY.value(), CONTENT, 1000, 500, DEADLINE);
    }

    private AiRequestSnapshot snapshot(AiRequestStatus status, String hash) {
        return new AiRequestSnapshot(REQUEST, status, hash);
    }

    private void prepareNewRequest() {
        when(requests.findByKey(USER, KEY)).thenReturn(Optional.empty());
        when(windows.getCurrentUsage(USER)).thenReturn(new UsageResponse(WINDOW, new BigDecimal("20000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("20000"), Instant.EPOCH, DEADLINE));
    }

    private void failReservation(RuntimeException failure) {
        when(reservation.reserve(USER, ROOM, WINDOW, KEY.value(), fingerprint, 1000, 500, DEADLINE)).thenThrow(failure);
    }

    private DataIntegrityViolationException integrity(String state, String constraint) {
        ConstraintViolationException cause = mock(ConstraintViolationException.class);
        when(cause.getSQLState()).thenReturn(state);
        when(cause.getConstraintName()).thenReturn(constraint);
        return new DataIntegrityViolationException("테스트용 저장 실패", new IllegalStateException(cause));
    }

    private void assertError(ErrorCode code, Runnable operation) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
