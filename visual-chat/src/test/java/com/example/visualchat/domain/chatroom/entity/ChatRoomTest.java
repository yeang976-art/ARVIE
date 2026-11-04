package com.example.visualchat.domain.chatroom.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ChatRoomTest {

    @Test
    @DisplayName("배정 기록에서 소유 회원을 정하고 기본 보관 기간을 90일로 설정한다")
    void createsRoomFromAssignmentWithDefaultRetention() {
        UserReve assignment = assignment();

        ChatRoom room = ChatRoom.of(assignment);

        assertThat(room.getId()).isNull();
        assertThat(room.getUser()).isSameAs(assignment.getUser());
        assertThat(room.getUserReve()).isSameAs(assignment);
        assertThat(room.getRetentionDays()).isEqualTo((short) 90);
    }

    @ParameterizedTest
    @ValueSource(shorts = {1, 90, 180})
    @DisplayName("보관 기간은 양 끝 경계값을 포함해 1일부터 180일까지 변경할 수 있다")
    void changesRetentionWithinAllowedRange(short days) {
        ChatRoom room = ChatRoom.of(assignment());

        room.modifyRetentionDays(days);

        assertThat(room.getRetentionDays()).isEqualTo(days);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(shorts = {Short.MIN_VALUE, -1, 0, 181, Short.MAX_VALUE})
    @DisplayName("유효하지 않은 보관 기간은 안내 메시지와 함께 거절하고 기존 설정을 유지한다")
    void rejectsInvalidRetentionWithoutChangingExistingValue(Short days) {
        ChatRoom room = ChatRoom.of(assignment());
        room.modifyRetentionDays((short) 30);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> room.modifyRetentionDays(days))
                .withMessage("보관 기간은 1일부터 180일까지 설정할 수 있습니다.")
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        assertThat(room.getRetentionDays()).isEqualTo((short) 30);
    }

    private UserReve assignment() {
        User user = User.of("arvie", "user@example.com", "encoded-password");
        Reve reve = Reve.of("REVE_FIRST", "루미", "소개", "설정", BigDecimal.ONE);
        return UserReve.of(user, reve);
    }
}
