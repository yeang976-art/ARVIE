package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.*;
import com.example.visualchat.domain.ai_request.port.AiChatClient;
import com.example.visualchat.domain.ai_request.util.AiRequestFingerprintGenerator;
import com.example.visualchat.domain.message.enums.SenderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** [ROLE] 멱등 재전송과 예약 전후 실패·완료 저장 불확실성의 조율을 검증 */
class AiChatCommandTest {
    private static final long USER = 41, ROOM = 73;
    private static final String KEY = "chat-key", CONTENT = "이번 사용자 원문";
    private static final UUID ID = UUID.fromString("b742a9e1-f339-4e51-bcdf-bf7eb76c6380");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final AiChatClient client = mock(AiChatClient.class);
    private final AiRequestService requests = mock(AiRequestService.class);
    private final AiRequestCommand writes = mock(AiRequestCommand.class);
    private final AiRequestStatusCommand statuses = mock(AiRequestStatusCommand.class);
    private final AiRequestAdmissionCommand admission = mock(AiRequestAdmissionCommand.class);
    private final AiChatPreparationCommand preparation = mock(AiChatPreparationCommand.class);
    private final AiChatPersistenceCommand persistence = mock(AiChatPersistenceCommand.class);
    private final AiRequestFingerprintGenerator fingerprints = new AiRequestFingerprintGenerator();
    private final AiChatCommand command = new AiChatCommand(Clock.fixed(NOW, ZoneOffset.UTC), client,
            requests, writes, statuses, admission, preparation, persistence, fingerprints);
    private final AiChatRequest input = new AiChatRequest("gpt-6.1-terra", "내부 설정",
            List.of(new AiChatMessage(SenderType.USER, CONTENT)), 1024);
    private final AiChatResult result = new AiChatResult("resp-test", "새 AI 답변", new AiTokenUsage(1000, 0, 0, 0, 200));

    @BeforeEach
    void startsWithoutExistingRequest() {
        when(requests.findByKey(USER, new IdempotencyKey(KEY))).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(AiRequestStatus.class)
    @DisplayName("기존 요청의 모든 상태는 계수·예약·생성·저장 없이 재조회한다")
    void replaysEveryState(AiRequestStatus state) {
        when(requests.findByKey(USER, new IdempotencyKey(KEY))).thenReturn(Optional.of(
                new AiRequestSnapshot(ID, state, fingerprints.generate(ROOM, CONTENT))));
        var expected = response(state);
        when(statuses.getStatus(USER, ID)).thenReturn(expected);
        assertThat(send()).isSameAs(expected);
        verifyNoInteractions(client, preparation, admission, persistence, writes);
    }

    @Test
    @DisplayName("같은 키에 다른 원문은 외부 호출 전에 충돌로 거절한다")
    void rejectsConflictingContent() {
        when(requests.findByKey(USER, new IdempotencyKey(KEY))).thenReturn(Optional.of(
                new AiRequestSnapshot(ID, AiRequestStatus.SUCCEEDED, "a".repeat(64))));
        error(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, this::send);
        verifyNoInteractions(client, preparation, admission, persistence, writes, statuses);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "space key", "한글", "bad/key"})
    @DisplayName("잘못된 멱등키는 저장소·외부 호출 전에 거절한다")
    void rejectsInvalidKey(String key) {
        error(ErrorCode.INVALID_INPUT, () -> command.send(USER, ROOM, key, CONTENT));
        verifyNoInteractions(client, preparation, admission, persistence, writes);
    }

    @Test
    @DisplayName("소유권 확인 실패 후에는 계수와 생성 모두 호출하지 않는다")
    void stopsBeforeProviderWhenNotOwned() {
        when(preparation.prepare(USER, ROOM, CONTENT)).thenThrow(new ServiceException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        error(ErrorCode.CHAT_ROOM_NOT_FOUND, this::send);
        verifyNoInteractions(client, admission, persistence, writes);
    }

    @Test
    @DisplayName("신규 요청은 계수·예약·사용자 저장·생성·완료 저장 순서를 지킨다")
    void executesOnlyAfterCommittedAdmission() {
        prepareNew(1000);
        when(client.generate(input)).thenReturn(result);
        when(statuses.getStatus(USER, ID)).thenReturn(response(AiRequestStatus.SUCCEEDED));
        assertThat(send().status()).isEqualTo(AiRequestStatus.SUCCEEDED);
        var order = inOrder(preparation, client, admission, persistence, statuses);
        order.verify(preparation).prepare(USER, ROOM, CONTENT);
        order.verify(client).countInputTokens(input);
        order.verify(admission).admit(USER, ROOM, KEY, CONTENT, 1000, 1024, NOW.plusSeconds(120));
        order.verify(persistence).saveUserMessage(USER, ID, CONTENT);
        order.verify(client).generate(input);
        order.verify(persistence).completeSuccess(USER, ID, result);
        order.verify(statuses).getStatus(USER, ID);
        verifyNoInteractions(writes);
    }

    @Test
    @DisplayName("계수 도중 같은 키가 먼저 접수되면 새 사용자 메시지나 답변을 만들지 않는다")
    void skipsGenerationAfterAdmissionRace() {
        prepareNew(1000);
        when(admission.admit(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(new AiRequestReceipt(ID, AiRequestStatus.PROCESSING, false));
        when(statuses.getStatus(USER, ID)).thenReturn(response(AiRequestStatus.PROCESSING));
        assertThat(send().status()).isEqualTo(AiRequestStatus.PROCESSING);
        verify(client, never()).generate(any());
        verifyNoInteractions(persistence, writes);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 16385, Long.MAX_VALUE})
    @DisplayName("잘못된 계수 결과나 입력 상한 초과는 예약 전에 거절한다")
    void rejectsInvalidInputCount(long count) {
        when(preparation.prepare(USER, ROOM, CONTENT)).thenReturn(input);
        when(client.countInputTokens(input)).thenReturn(count);
        error(count <= 0 ? ErrorCode.AI_PROVIDER_INVALID_RESPONSE : ErrorCode.INVALID_INPUT, this::send);
        verifyNoInteractions(admission, persistence, writes);
        verify(client, never()).generate(any());
    }

    @Test
    @DisplayName("입력 상한과 같은 계수는 허용한다")
    void acceptsInputBoundary() {
        prepareNew(16384);
        when(client.generate(input)).thenReturn(result);
        when(statuses.getStatus(USER, ID)).thenReturn(response(AiRequestStatus.SUCCEEDED));
        assertThat(send().status()).isEqualTo(AiRequestStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("예약이 거절되면 사용자 저장과 생성 호출을 하지 않는다")
    void stopsWhenReservationFails() {
        prepareNew(1000);
        when(admission.admit(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new ServiceException(ErrorCode.INVALID_INPUT));
        error(ErrorCode.INVALID_INPUT, this::send);
        verifyNoInteractions(persistence, writes);
        verify(client, never()).generate(any());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE", "AI_PROVIDER_INVALID_RESPONSE"})
    @DisplayName("제공사 실패는 해당 코드로 실패 정산하고 성공 저장하지 않는다")
    void finalizesProviderFailure(ErrorCode code) {
        prepareNew(1000);
        when(client.generate(input)).thenThrow(new ServiceException(code));
        error(code, this::send);
        verify(writes).completeFailure(USER, ID, code);
        verify(persistence, never()).completeSuccess(anyLong(), any(), any());
    }

    @Test
    @DisplayName("사용자 저장 실패는 생성 없이 실패 정산한다")
    void finalizesUserSaveFailure() {
        prepareNew(1000);
        doThrow(new IllegalStateException("private-error")).when(persistence).saveUserMessage(USER, ID, CONTENT);
        error(ErrorCode.SERVER_ERROR, this::send);
        verify(client, never()).generate(any());
        verify(writes).completeFailure(USER, ID, ErrorCode.SERVER_ERROR);
    }

    @Test
    @DisplayName("실패 정산 자체가 실패하면 완료로 가장하지 않고 서버 오류를 반환한다")
    void reportsCleanupFailure() {
        prepareNew(1000);
        when(client.generate(input)).thenThrow(new ServiceException(ErrorCode.AI_PROVIDER_TIMEOUT));
        doThrow(new IllegalStateException("private-error")).when(writes).completeFailure(USER, ID, ErrorCode.AI_PROVIDER_TIMEOUT);
        error(ErrorCode.SERVER_ERROR, this::send);
        verifyNoInteractions(statuses);
    }

    @Test
    @DisplayName("완료 저장 실패는 실패 상태로 덮어쓰거나 외부 생성을 반복하지 않는다")
    void preservesUnknownCompletionOutcome() {
        prepareNew(1000);
        when(client.generate(input)).thenReturn(result);
        doThrow(new IllegalStateException("private-error")).when(persistence).completeSuccess(USER, ID, result);
        error(ErrorCode.SERVER_ERROR, this::send);
        verifyNoInteractions(writes, statuses);
        verify(client, times(1)).generate(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-result", "null-usage", "excess-input", "excess-output"})
    @DisplayName("정산 가능한 결과가 없거나 예약에 사용한 상한을 넘으면 성공 저장하지 않는다")
    void rejectsInvalidResult(String kind) {
        prepareNew(1000);
        AiChatResult invalid = switch (kind) {
            case "null-result" -> null;
            case "null-usage" -> new AiChatResult("resp-test", "답변", null);
            case "excess-input" -> new AiChatResult("resp-test", "답변", new AiTokenUsage(1001, 0, 0, 0, 200));
            default -> new AiChatResult("resp-test", "답변", new AiTokenUsage(1000, 0, 0, 0, 1025));
        };
        when(client.generate(input)).thenReturn(invalid);
        error(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, this::send);
        verify(writes).completeFailure(USER, ID, ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        verify(persistence, never()).completeSuccess(anyLong(), any(), any());
    }

    private void prepareNew(long tokens) {
        when(preparation.prepare(USER, ROOM, CONTENT)).thenReturn(input);
        when(client.countInputTokens(input)).thenReturn(tokens);
        when(admission.admit(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(AiRequestReceipt.created(ID));
    }

    private AiRequestStatusResponse send() { return command.send(USER, ROOM, KEY, CONTENT); }
    private AiRequestStatusResponse response(AiRequestStatus state) {
        return new AiRequestStatusResponse(ID, ROOM, state, BigDecimal.ZERO, null, NOW, NOW.plusSeconds(120), null, null);
    }
    private void error(ErrorCode code, Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run).satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("private-error");
        });
    }
}
