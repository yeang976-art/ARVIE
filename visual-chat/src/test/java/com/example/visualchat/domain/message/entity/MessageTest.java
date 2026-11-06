package com.example.visualchat.domain.message.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.message.enums.SenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MessageTest {

    @ParameterizedTest
    @EnumSource(SenderType.class)
    @DisplayName("사용자와 AI 메시지는 연결 정보와 본문을 그대로 보관하고 기본 90일 후 만료된다")
    void createsMessageWithOriginalContentAndDefaultRetention(SenderType sender) {
        Parents parents = parents((short) 90);
        String content = "  안녕, 레브 🌷\n두 번째 줄도 그대로 보관해요.  ";
        Instant before = Instant.now();

        Message message = Message.of(parents.room(), parents.request(), sender, content);

        assertThat(message.getId()).isNull();
        assertThat(message.getChatRoom()).isSameAs(parents.room());
        assertThat(message.getAiRequest()).isSameAs(parents.request());
        assertThat(message.getSender()).isEqualTo(sender);
        assertThat(message.getContent()).isEqualTo(content);
        assertThat(message.getSentAt()).isBetween(before, Instant.now());
        assertThat(Duration.between(message.getSentAt(), message.getExpiresAt())).isEqualTo(Duration.ofDays(90));
    }

    @ParameterizedTest
    @CsvSource({
            "USER,1,2026-01-02T00:00:00Z",
            "USER,90,2026-04-01T00:00:00Z",
            "USER,180,2026-06-30T00:00:00Z",
            "ASSISTANT,1,2026-01-02T00:00:00Z",
            "ASSISTANT,90,2026-04-01T00:00:00Z",
            "ASSISTANT,180,2026-06-30T00:00:00Z"
    })
    @DisplayName("두 작성자 모두 보관 기간의 1일·90일·180일 경계값을 전송 시각에 더한다")
    void calculatesExpiryAtRetentionBoundaries(SenderType sender, short days, String expectedExpiry) {
        Parents parents = parents(days);
        Instant sentAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse(expectedExpiry);
        Message message;

        // [WHY] 실제로 기다리지 않고 월 경계를 지나는 고정 일수 계산을 재현한다.
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(sentAt);
            message = Message.of(parents.room(), parents.request(), sender, "본문");
        }

        assertThat(message.getSentAt()).isEqualTo(sentAt);
        assertThat(message.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("AI 답변의 만료 시각은 사용자 원문이 아닌 답변 자신의 전송 시각을 기준으로 계산한다")
    void calculatesIndependentExpiryForUserAndAssistant() {
        Parents parents = parents((short) 90);
        Instant userSentAt = Instant.parse("2026-01-01T12:00:00Z");
        Instant assistantSentAt = Instant.parse("2026-01-02T12:00:00Z");
        Instant userExpiry = Instant.parse("2026-04-01T12:00:00Z");
        Instant assistantExpiry = Instant.parse("2026-04-02T12:00:00Z");
        Message userMessage;
        Message assistantMessage;

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(userSentAt, assistantSentAt);
            userMessage = Message.of(parents.room(), parents.request(), SenderType.USER, "질문");
            assistantMessage = Message.of(parents.room(), parents.request(), SenderType.ASSISTANT, "답변");
        }

        assertThat(userMessage.getSentAt()).isEqualTo(userSentAt);
        assertThat(userMessage.getExpiresAt()).isEqualTo(userExpiry);
        assertThat(assistantMessage.getSentAt()).isEqualTo(assistantSentAt);
        assertThat(assistantMessage.getExpiresAt()).isEqualTo(assistantExpiry);
        assertThat(assistantMessage.getExpiresAt()).isAfter(userMessage.getExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"room", "roomId", "request", "requestId"})
    @DisplayName("저장된 식별자가 없는 채팅방과 AI 요청으로 메시지를 만들 수 없다")
    void rejectsMissingOrUnsavedAssociation(String missing) {
        Parents parents = parents((short) 90);
        ChatRoom room = "room".equals(missing) ? null : parents.room();
        AiRequest request = "request".equals(missing) ? null : parents.request();
        if ("roomId".equals(missing)) {
            when(parents.room().getId()).thenReturn(null);
        } else if ("requestId".equals(missing)) {
            when(parents.request().getId()).thenReturn(null);
        }

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> Message.of(room, request, SenderType.USER, "질문"))
                .withMessage("저장된 채팅방과 AI 요청이 필요합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("요청과 다른 채팅방의 보관 기간으로 메시지를 생성할 수 없다")
    void rejectsRoomDifferentFromRequestBeforeCalculatingExpiry() {
        Parents parents = parents((short) 90);
        ChatRoom otherRoom = mock(ChatRoom.class);
        when(otherRoom.getId()).thenReturn(2L);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> Message.of(otherRoom, parents.request(), SenderType.USER, "질문"))
                .withMessage("AI 요청과 메시지는 같은 채팅방에 속해야 합니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    // [WHY] 메시지 생성 규칙은 실제 객체로 검증하고 부모의 저장된 식별자와 보관 설정만 제공한다.
    private Parents parents(short days) {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(1L);
        when(room.getRetentionDays()).thenReturn(days);
        AiRequest request = mock(AiRequest.class);
        when(request.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(request.getChatRoom()).thenReturn(room);
        return new Parents(room, request);
    }

    private record Parents(ChatRoom room, AiRequest request) {
    }
}
