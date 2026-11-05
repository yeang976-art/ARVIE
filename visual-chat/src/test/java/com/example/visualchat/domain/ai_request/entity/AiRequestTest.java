package com.example.visualchat.domain.ai_request.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRequestTest {

    @Test
    @DisplayName("새 요청은 처리 중 상태로 생성되고 확정 차감과 완료 정보는 비어 있다")
    void createsProcessingRequestWithNoChargeOrResult() {
        Parents parents = parents();
        Instant before = Instant.now().minusNanos(1_000);
        Instant deadline = Instant.now().plusSeconds(60);

        AiRequest request = create(parents, new BigDecimal("200.123456"), deadline);

        assertThat(request.getId()).isNull();
        assertThat(request.getUser()).isSameAs(parents.user());
        assertThat(request.getChatRoom()).isSameAs(parents.room());
        assertThat(request.getAccountUsageWindow()).isSameAs(parents.window());
        assertThat(request.getUsageWindowId()).isEqualTo(30L);
        assertThat(request.getIdempotencyKey()).isEqualTo("request-key");
        assertThat(request.getRequestFingerprint()).isEqualTo("a".repeat(64));
        assertThat(request.getProvider()).isEqualTo("test-provider");
        assertThat(request.getModelName()).isEqualTo("test-model");
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request.getReservedVie()).isEqualByComparingTo("200.123456");
        assertThat(request.getChargedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getStartedAt()).isBetween(before, Instant.now());
        assertThat(request.getProcessingDeadlineAt()).isEqualTo(deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(request.getFinishedAt()).isNull();
        assertThat(request.getErrorCode()).isNull();
        assertThat(request.getProviderRequestId()).isNull();
        assertThat(request.getInputTokens()).isNull();
        assertThat(request.getOutputTokens()).isNull();
        assertThat(request.getMeteringVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000001", "1.123456000", "999999999999.999999"})
    @DisplayName("예약량은 0과 최소 소수 단위, numeric 최댓값을 정확히 표현할 수 있다")
    void acceptsRepresentableReservationBoundaries(String value) {
        AiRequest request = create(parents(), new BigDecimal(value), Instant.now().plusSeconds(60));

        assertThat(request.getReservedVie()).isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "-0.000001", "0.0000001", "1.1234567", "1000000000000"})
    @DisplayName("누락·음수·반올림 필요·저장 범위 초과 예약량은 생성 단계에서 거절한다")
    void rejectsInvalidReservation(String value) {
        BigDecimal amount = value == null ? null : new BigDecimal(value);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> create(parents(), amount, Instant.now().plusSeconds(60)))
                .satisfies(exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(exception.getMessage()).contains("예약량");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "userId", "room", "roomId", "window", "windowId"})
    @DisplayName("저장된 식별자가 없는 회원·채팅방·이용 주기로 요청을 만들 수 없다")
    void rejectsMissingOrUnsavedParent(String missing) {
        Parents parents = parents();
        Parents invalid = switch (missing) {
            case "user" -> new Parents(null, parents.room(), parents.window());
            case "room" -> new Parents(parents.user(), null, parents.window());
            case "window" -> new Parents(parents.user(), parents.room(), null);
            default -> parents;
        };
        if ("userId".equals(missing)) {
            when(parents.user().getId()).thenReturn(null);
        } else if ("roomId".equals(missing)) {
            when(parents.room().getId()).thenReturn(null);
        } else if ("windowId".equals(missing)) {
            when(parents.window().getId()).thenReturn(null);
        }

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> create(invalid, new BigDecimal("200"), Instant.now().plusSeconds(60)))
                .withMessage("저장된 회원, 채팅방, 이용 주기가 필요합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"room", "window"})
    @DisplayName("다른 회원의 채팅방 또는 이용 주기는 생성 단계에서 거절한다")
    void rejectsMismatchedOwner(String target) {
        Parents parents = parents();
        User other = mock(User.class);
        when(other.getId()).thenReturn(2L);
        if ("room".equals(target)) {
            when(parents.room().getUser()).thenReturn(other);
        } else {
            when(parents.window().getUser()).thenReturn(other);
        }

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> create(parents, new BigDecimal("200"), Instant.now().plusSeconds(60)))
                .withMessage("채팅방과 이용 주기는 요청 회원의 소유여야 합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PAST", "NOW"})
    @DisplayName("기한이 없거나 시작 이후가 아니면 요청 생성을 거절한다")
    void rejectsMissingOrNonFutureDeadline(String value) {
        Instant deadline = value == null ? null : "PAST".equals(value) ? Instant.now().minusSeconds(1) : Instant.now();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> create(parents(), new BigDecimal("200"), deadline))
                .withMessage("처리 기한은 요청 시작 시각보다 뒤여야 합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000001", "123.456789000", "999999999999.999999"})
    @DisplayName("성공 시 실제 차감량과 완료 시각을 기록하고 최초 예약량은 보존한다")
    void succeedsWithExactCharge(String value) {
        AiRequest request = request();
        Instant before = Instant.now().minusNanos(1_000);

        request.success(new BigDecimal(value));

        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.SUCCEEDED);
        assertThat(request.getChargedVie()).isEqualByComparingTo(value);
        assertThat(request.getReservedVie()).isEqualByComparingTo("200");
        assertThat(request.getFinishedAt()).isBetween(before, Instant.now());
        assertThat(request.getErrorCode()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "-0.000001", "0.0000001", "1.1234567", "1000000000000"})
    @DisplayName("잘못된 차감량으로 완료하면 상태와 금액, 종료 시각을 변경하지 않는다")
    void rejectsInvalidChargeWithoutCompleting(String value) {
        AiRequest request = request();
        BigDecimal charge = value == null ? null : new BigDecimal(value);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> request.success(charge))
                .satisfies(exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(exception.getMessage()).contains("차감량");
                });
        assertProcessingWithoutCharge(request);
    }

    @Test
    @DisplayName("실패 시 정제된 오류 코드와 완료 시각을 기록하고 차감량은 0으로 유지한다")
    void failsWithoutChargeAndPreservesOriginalReservation() {
        AiRequest request = request();

        request.fail(ErrorCode.SERVER_ERROR);

        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(request.getChargedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getReservedVie()).isEqualByComparingTo("200");
        assertThat(request.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR.getCode());
        assertThat(request.getFinishedAt()).isAfterOrEqualTo(request.getStartedAt());
    }

    @Test
    @DisplayName("오류 코드 없는 실패 처리는 요청을 변경하지 않고 거절한다")
    void rejectsMissingFailureCodeWithoutMutation() {
        AiRequest request = request();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> request.fail(null))
                .withMessage("실패 원인 코드가 필요합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertProcessingWithoutCharge(request);
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,SUCCEEDED", "SUCCEEDED,FAILED", "FAILED,SUCCEEDED", "FAILED,FAILED"})
    @DisplayName("완료 요청의 중복 또는 반대 결과 처리는 기존 정산 결과를 변경하지 않는다")
    void rejectsEveryRepeatedCompletion(AiRequestStatus initial, AiRequestStatus target) {
        AiRequest request = request();
        complete(request, initial);
        BigDecimal charge = request.getChargedVie();
        String error = request.getErrorCode();
        Instant finishedAt = request.getFinishedAt();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> complete(request, target))
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_AI_REQUEST_STATUS));

        assertThat(request.getStatus()).isEqualTo(initial);
        assertThat(request.getChargedVie()).isEqualByComparingTo(charge);
        assertThat(request.getErrorCode()).isEqualTo(error);
        assertThat(request.getFinishedAt()).isEqualTo(finishedAt);
    }

    private void complete(AiRequest request, AiRequestStatus status) {
        if (status == AiRequestStatus.SUCCEEDED) {
            request.success(new BigDecimal("123.456789"));
        } else {
            request.fail(ErrorCode.SERVER_ERROR);
        }
    }

    private void assertProcessingWithoutCharge(AiRequest request) {
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request.getReservedVie()).isEqualByComparingTo("200");
        assertThat(request.getChargedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getFinishedAt()).isNull();
        assertThat(request.getErrorCode()).isNull();
    }

    private AiRequest request() {
        return create(parents(), new BigDecimal("200"), Instant.now().plusSeconds(60));
    }

    private AiRequest create(Parents parents, BigDecimal reservation, Instant deadline) {
        return AiRequest.of(parents.user(), parents.room(), parents.window(), "request-key", "a".repeat(64),
                "test-provider", "test-model", reservation, deadline);
    }

    // [WHY] 요청 자체의 동작은 실제 엔티티로 검증하고 부모의 저장된 식별자만 테스트 대역으로 제공한다.
    private Parents parents() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(20L);
        when(room.getUser()).thenReturn(user);
        AccountUsageWindow window = mock(AccountUsageWindow.class);
        when(window.getId()).thenReturn(30L);
        when(window.getUser()).thenReturn(user);
        return new Parents(user, room, window);
    }

    private record Parents(User user, ChatRoom room, AccountUsageWindow window) {
    }
}
