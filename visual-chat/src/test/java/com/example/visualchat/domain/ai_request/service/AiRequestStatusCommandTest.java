package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.message.dto.MessageResponse;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.service.MessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** [ROLE] 소유권 확인 순서와 완료 상태별 답변 조회 및 판정 시각 사용을 검증 */
class AiRequestStatusCommandTest {
    private static final UUID REQUEST = UUID.fromString("88888888-1111-4222-8333-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00.123456Z");
    private final AiRequestService requests = mock(AiRequestService.class);
    private final MessageService messages = mock(MessageService.class);
    private final Clock clock = mock(Clock.class);
    private final AiRequestStatusCommand command = new AiRequestStatusCommand(clock, messages, requests);

    @ParameterizedTest
    @EnumSource(value = AiRequestStatus.class, names = {"PROCESSING", "FAILED"})
    @DisplayName("처리 중·실패 요청은 답변 조회와 시간 판정 없이 저장된 상태를 반환한다")
    void returnsIncompleteStatusWithoutLoadingMessages(AiRequestStatus status) {
        AiRequestStatusResponse response = response(status);
        when(requests.getStatus(41L, REQUEST)).thenReturn(response);
        assertThat(command.getStatus(41L, REQUEST)).isSameAs(response);
        verifyNoInteractions(messages, clock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("성공 요청은 소유권 확인 뒤 한 번 정한 시각으로 답변을 조회하고 없으면 null을 반환한다")
    void loadsOnlyCurrentlyVisibleAnswer(boolean exists) {
        AiRequestStatusResponse response = response(AiRequestStatus.SUCCEEDED);
        MessageResponse answer = new MessageResponse(99L, SenderType.ASSISTANT, "현재 답변", NOW, NOW.plusSeconds(60));
        when(requests.getStatus(41L, REQUEST)).thenReturn(response);
        when(clock.instant()).thenReturn(NOW);
        when(messages.findVisibleAnswer(REQUEST, 73L, NOW)).thenReturn(exists ? Optional.of(answer) : Optional.empty());
        AiRequestStatusResponse result = command.getStatus(41L, REQUEST);
        assertThat(result.answer()).isEqualTo(exists ? answer : null);
        assertThat(result.requestId()).isEqualTo(REQUEST);
        assertThat(result.chargedVie()).isEqualByComparingTo("20");
        var order = inOrder(requests, clock, messages);
        order.verify(requests).getStatus(41L, REQUEST);
        order.verify(clock).instant();
        order.verify(messages).findVisibleAnswer(REQUEST, 73L, NOW);
        verify(clock, times(1)).instant();
    }

    @Test
    @DisplayName("타인 또는 없는 요청은 메시지를 조회하기 전에 거절한다")
    void checksOwnershipBeforeReadingAnswer() {
        ServiceException failure = new ServiceException(ErrorCode.AI_REQUEST_NOT_FOUND);
        when(requests.getStatus(41L, REQUEST)).thenThrow(failure);
        assertThatThrownBy(() -> command.getStatus(41L, REQUEST)).isSameAs(failure);
        verifyNoInteractions(messages, clock);
    }

    private AiRequestStatusResponse response(AiRequestStatus status) {
        return new AiRequestStatusResponse(REQUEST, 73L, status,
                status == AiRequestStatus.SUCCEEDED ? new BigDecimal("20") : BigDecimal.ZERO,
                status == AiRequestStatus.FAILED ? ErrorCode.SERVER_ERROR.getCode() : null,
                NOW.minusSeconds(120), NOW.minusSeconds(60),
                status == AiRequestStatus.PROCESSING ? null : NOW.minusSeconds(30), null);
    }
}
