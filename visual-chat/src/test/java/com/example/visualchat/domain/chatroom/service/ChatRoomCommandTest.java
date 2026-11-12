package com.example.visualchat.domain.chatroom.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.chatroom.dto.ChatRoomResponse;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.service.UserReveService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** [ROLE] 채팅방 열기의 기존 방 재사용과 복구 가능한 유일성 충돌의 범위를 검증 */
class ChatRoomCommandTest {

    private final UserReveService assignments = mock(UserReveService.class);
    private final ChatRoomService rooms = mock(ChatRoomService.class);
    private final UserReve assignment = mock(UserReve.class);
    private final ChatRoomCommand command = new ChatRoomCommand(assignments, rooms);
    private final ChatRoomResponse response = new ChatRoomResponse(101L, (short) 90);

    @BeforeEach
    void prepareAssignment() {
        when(assignment.getId()).thenReturn(89L);
        when(assignments.getOwnedAssignment(41L, 73L)).thenReturn(assignment);
    }

    @Test
    @DisplayName("기존 방이 있으면 소유권을 확인하고 새 방을 생성하지 않는다")
    void returnsExistingRoom() {
        when(rooms.findByAssignment(41L, 89L)).thenReturn(Optional.of(response));

        assertThat(command.open(41L, 73L)).isEqualTo(response);
        var order = inOrder(assignments, rooms);
        order.verify(assignments).getOwnedAssignment(41L, 73L);
        order.verify(rooms).findByAssignment(41L, 89L);
        verify(rooms, never()).create(any());
    }

    @Test
    @DisplayName("기존 방이 없으면 확인된 배정으로 생성한 방을 반환한다")
    void createsFirstRoom() {
        when(rooms.findByAssignment(41L, 89L)).thenReturn(Optional.empty());
        when(rooms.create(assignment)).thenReturn(response);

        assertThat(command.open(41L, 73L)).isEqualTo(response);
        verify(rooms).create(assignment);
    }

    @Test
    @DisplayName("미보유 레브라면 채팅방 조회나 생성을 시도하지 않는다")
    void stopsBeforeRoomAccessForUnownedReve() {
        when(assignments.getOwnedAssignment(41L, 73L)).thenThrow(new ServiceException(ErrorCode.USER_REVE_NOT_FOUND));

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> command.open(41L, 73L))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.USER_REVE_NOT_FOUND));
        verifyNoInteractions(rooms);
    }

    @Test
    @DisplayName("배정당 채팅방 유일성 충돌이면 재생성 없이 먼저 커밋된 방을 조회한다")
    void recoversOnlyDuplicateRoomCreation() {
        DataIntegrityViolationException failure = integrityError("23505", "uk_chat_rooms_user_reves");
        when(rooms.findByAssignment(41L, 89L)).thenReturn(Optional.empty()).thenReturn(Optional.of(response));
        when(rooms.create(assignment)).thenThrow(failure);

        assertThat(command.open(41L, 73L)).isEqualTo(response);
        var order = inOrder(rooms);
        order.verify(rooms).findByAssignment(41L, 89L);
        order.verify(rooms).create(assignment);
        order.verify(rooms).findByAssignment(41L, 89L);
        verify(rooms, times(1)).create(assignment);
    }

    @ParameterizedTest
    @CsvSource(value = {"23503,uk_chat_rooms_user_reves", "23505,another_constraint", "23505,<null>"}, nullValues = "<null>")
    @DisplayName("다른 SQL 상태나 유일성 제약 위반은 성공 응답으로 복구하지 않는다")
    void propagatesUnrelatedIntegrityErrors(String state, String constraint) {
        DataIntegrityViolationException failure = integrityError(state, constraint);
        assertUnrecoverable(failure);
    }

    @Test
    @DisplayName("판별할 DB 제약 정보가 없는 무결성 오류는 그대로 전달한다")
    void propagatesUnknownIntegrityErrors() {
        assertUnrecoverable(new DataIntegrityViolationException("테스트용 무결성 오류"));
    }

    @Test
    @DisplayName("유일성 충돌 후 기존 방도 찾지 못하면 서버 오류로 처리한다")
    void rejectsInconsistentRecoveryResult() {
        DataIntegrityViolationException failure = integrityError("23505", "uk_chat_rooms_user_reves");
        when(rooms.findByAssignment(41L, 89L)).thenReturn(Optional.empty());
        when(rooms.create(assignment)).thenThrow(failure);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> command.open(41L, 73L))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVER_ERROR));
        verify(rooms, times(1)).create(assignment);
    }

    private void assertUnrecoverable(DataIntegrityViolationException failure) {
        when(rooms.findByAssignment(41L, 89L)).thenReturn(Optional.empty());
        when(rooms.create(assignment)).thenThrow(failure);

        assertThatThrownBy(() -> command.open(41L, 73L)).isSameAs(failure);
        verify(rooms, times(1)).findByAssignment(41L, 89L);
    }

    private DataIntegrityViolationException integrityError(String state, String constraint) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getSQLState()).thenReturn(state);
        when(violation.getConstraintName()).thenReturn(constraint);
        return new DataIntegrityViolationException("테스트용 저장 실패", new IllegalStateException(violation));
    }
}
