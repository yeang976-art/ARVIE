package com.example.visualchat.domain.ai_request.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** [ROLE] 예약 당시 정책과 재검산에 필요한 토큰 사용량 및 정산 상한을 검증 */
class AiRequestMeteringTest {

    @Test
    @DisplayName("예약 시 정책 버전을 고정하고 성공 시 캐시 유형별 원시 사용량을 보존한다")
    void preservesVersionAndDetailedUsage() {
        AiRequest request = request("terra-v1", "500");
        AiTokenUsage usage = new AiTokenUsage(12000, 4000, 3000, 4000, 1000);
        request.success(usage, new BigDecimal("199.5"));
        assertThat(request.getMeteringVersion()).isEqualTo("terra-v1");
        assertThat(request.getInputTokens()).isEqualTo(12000L);
        assertThat(request.getCachedInputTokens()).isEqualTo(4000L);
        assertThat(request.getCacheWrite30mTokens()).isEqualTo(3000L);
        assertThat(request.getCacheWrite12hTokens()).isEqualTo(4000L);
        assertThat(request.getOutputTokens()).isEqualTo(1000L);
        assertThat(request.getChargedVie()).isEqualByComparingTo("199.5");
        assertThat(request.getReservedVie()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("numeric 최댓값의 예약과 동일한 차감량은 정확히 기록한다")
    void acceptsMaximumChargeWithinReservation() {
        AiRequest request = request("terra-v1", "999999999999.999999");
        request.success(new AiTokenUsage(0, 0, 0, 0, 0), new BigDecimal("999999999999.999999"));
        assertThat(request.getChargedVie()).isEqualByComparingTo("999999999999.999999");
    }

    @Test
    @DisplayName("사용량 누락과 예약 초과 차감은 상태·금액·사용량을 변경하지 않는다")
    void rejectsIncompleteOrExcessiveSettlementWithoutMutation() {
        AiRequest request = request("terra-v1", "50");
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> request.success(null, BigDecimal.ZERO));
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> request.success(new AiTokenUsage(1000, 0, 0, 0, 200), new BigDecimal("50.000001")));
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request.getChargedVie()).isZero();
        assertThat(request.getFinishedAt()).isNull();
        assertThat(request.getInputTokens()).isNull();
        assertThat(request.getCachedInputTokens()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("요율 버전이 누락되거나 공백이면 예약 기록을 만들 수 없다")
    void rejectsMissingVersion(String version) {
        assertInvalidVersion(version);
    }

    @Test
    @DisplayName("정책 버전의 최대 길이 64자는 허용하고 65자는 거절한다")
    void validatesVersionLengthBoundary() {
        assertThat(request("v".repeat(64), "50").getMeteringVersion()).hasSize(64);
        assertInvalidVersion("v".repeat(65));
    }

    private void assertInvalidVersion(String version) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(() -> request(version, "50"))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    private AiRequest request(String version, String reservation) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(2L);
        when(room.getUser()).thenReturn(user);
        AccountUsageWindow window = mock(AccountUsageWindow.class);
        when(window.getId()).thenReturn(3L);
        when(window.getUser()).thenReturn(user);
        return AiRequest.of(user, room, window, "key", "a".repeat(64), "OPENAI", "gpt-6.1-terra",
                version, new BigDecimal(reservation), Instant.now().plusSeconds(60));
    }
}
