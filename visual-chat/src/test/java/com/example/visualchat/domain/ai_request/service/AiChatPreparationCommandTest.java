package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.service.ChatRoomService;
import com.example.visualchat.domain.message.dto.MessageResponse;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.service.MessageService;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;

/** [ROLE] 소유권·입력 경계와 엔티티 없는 대화 입력 스냅샷 구성을 검증 */
class AiChatPreparationCommandTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final ChatRoomService rooms = mock(ChatRoomService.class);
    private final MessageService messages = mock(MessageService.class);
    private final AiChatPreparationCommand command = new AiChatPreparationCommand(Clock.fixed(NOW, ZoneOffset.UTC), messages, rooms);

    @Test
    @DisplayName("과거 대화 뒤에 이번 입력을 한 번만 붙이고 레브 설정과 출력 상한을 전달한다")
    void snapshotsContextAndCurrentMessage() {
        owned("비공개 레브 설정");
        when(messages.findRecentContext(41L, 73L, NOW)).thenReturn(List.of(
                new MessageResponse(1L, SenderType.USER, "이전 질문", NOW.minusSeconds(2), NOW.plusSeconds(30)),
                new MessageResponse(2L, SenderType.ASSISTANT, "이전 답변", NOW.minusSeconds(1), NOW.plusSeconds(30))));
        var result = command.prepare(41L, 73L, " 새 질문🌸\n");
        assertThat(result.modelName()).isEqualTo("gpt-6.1-terra");
        assertThat(result.systemPrompt()).isEqualTo("비공개 레브 설정");
        assertThat(result.maxOutputTokens()).isEqualTo(1024);
        assertThat(result.messages()).extracting(message -> message.content())
                .containsExactly("이전 질문", "이전 답변", " 새 질문🌸\n");
        assertThat(result.messages().getLast().sender()).isEqualTo(SenderType.USER);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @DisplayName("공백 입력은 도메인 조회 전에 거절한다")
    void rejectsBlankContent(String text) {
        invalid(ErrorCode.INVALID_INPUT, () -> command.prepare(41L, 73L, text));
        verifyNoInteractions(rooms, messages);
    }

    @Test
    @DisplayName("입력 길이 2000자는 허용하고 2001자는 조회 전에 거절한다")
    void verifiesLengthBoundary() {
        invalid(ErrorCode.INVALID_INPUT, () -> command.prepare(41L, 73L, "가".repeat(2001)));
        verifyNoInteractions(rooms, messages);
        owned("설정");
        when(messages.findRecentContext(41L, 73L, NOW)).thenReturn(List.of());
        assertThat(command.prepare(41L, 73L, "가".repeat(2000)).messages().getFirst().content()).hasSize(2000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("레브 설정이 없으면 대화 원문을 조회하지 않고 서버 오류로 처리한다")
    void rejectsMissingPrompt(String prompt) {
        owned(prompt);
        invalid(ErrorCode.SERVER_ERROR, () -> command.prepare(41L, 73L, "질문"));
        verifyNoInteractions(messages);
    }

    @Test
    @DisplayName("소유권 확인에 실패하면 대화 목록을 조회하지 않는다")
    void verifiesOwnershipFirst() {
        when(rooms.getOwnedRoom(41L, 73L)).thenThrow(new ServiceException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        invalid(ErrorCode.CHAT_ROOM_NOT_FOUND, () -> command.prepare(41L, 73L, "질문"));
        verifyNoInteractions(messages);
    }

    private void owned(String prompt) {
        ChatRoom room = mock(ChatRoom.class);
        UserReve assignment = mock(UserReve.class);
        Reve reve = mock(Reve.class);
        when(rooms.getOwnedRoom(41L, 73L)).thenReturn(room);
        when(room.getUserReve()).thenReturn(assignment);
        when(assignment.getReve()).thenReturn(reve);
        when(reve.getSystemPrompt()).thenReturn(prompt);
    }

    private void invalid(ErrorCode code, Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
