package com.example.visualchat.domain.ai_request.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;

/** [ROLE] 제공사 응답 식별자의 길이·필수값·완료 후 변경 금지를 검증 */
class AiRequestProviderResponseTest {
    @Test
    @DisplayName("255자 응답 ID는 처리 중 한 번 기록할 수 있고 덮어쓸 수 없다")
    void recordsOnceAtLengthBoundary() {
        AiRequest request = request();
        request.recordProviderResponse("r".repeat(255));
        assertThat(request.getProviderRequestId()).hasSize(255);
        error(ErrorCode.INVALID_AI_REQUEST_STATUS, () -> request.recordProviderResponse("other"));
        assertThat(request.getProviderRequestId()).isEqualTo("r".repeat(255));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("응답 ID 누락과 공백은 기록하지 않는다")
    void rejectsMissingId(String id) {
        AiRequest request = request();
        error(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, () -> request.recordProviderResponse(id));
        assertThat(request.getProviderRequestId()).isNull();
    }

    @Test
    @DisplayName("256자 ID와 성공·실패 완료 후의 ID 변경을 거절한다")
    void rejectsTooLongAndTerminalChanges() {
        AiRequest processing = request();
        error(ErrorCode.AI_PROVIDER_INVALID_RESPONSE, () -> processing.recordProviderResponse("r".repeat(256)));
        AiRequest succeeded = request();
        succeeded.success(new AiTokenUsage(1000, 0, 0, 0, 200), new BigDecimal("20"));
        error(ErrorCode.INVALID_AI_REQUEST_STATUS, () -> succeeded.recordProviderResponse("resp-late"));
        AiRequest failed = request();
        failed.fail(ErrorCode.SERVER_ERROR);
        error(ErrorCode.INVALID_AI_REQUEST_STATUS, () -> failed.recordProviderResponse("resp-late"));
    }

    private AiRequest request() {
        User user = mock(User.class);
        ChatRoom room = mock(ChatRoom.class);
        AccountUsageWindow window = mock(AccountUsageWindow.class);
        when(user.getId()).thenReturn(1L);
        when(room.getId()).thenReturn(2L);
        when(room.getUser()).thenReturn(user);
        when(window.getId()).thenReturn(3L);
        when(window.getUser()).thenReturn(user);
        return AiRequest.of(user, room, window, "key", "a".repeat(64), "OPENAI", "gpt-6.1-terra", "terra-v1",
                new BigDecimal("76.2"), Instant.now().plusSeconds(120));
    }

    private void error(ErrorCode code, Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
