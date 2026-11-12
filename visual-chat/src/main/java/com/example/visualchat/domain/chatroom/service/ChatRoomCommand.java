package com.example.visualchat.domain.chatroom.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.chatroom.dto.ChatRoomResponse;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.service.UserReveService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** [ROLE] 배정 소유권 확인과 채팅방 열기·동시 생성 충돌 복구를 조율 */
@Service
@RequiredArgsConstructor
public class ChatRoomCommand {

    private final UserReveService userReveService;
    private final ChatRoomService chatRoomService;

    // [WHY] 생성 실패의 롤백이 완료된 뒤 별도 트랜잭션으로 기존 방을 조회
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatRoomResponse open(Long userId, Long reveId) {
        UserReve assignment = userReveService.getOwnedAssignment(userId, reveId);

        Optional<ChatRoomResponse> existing = chatRoomService.findByAssignment(userId, assignment.getId());

        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return chatRoomService.create(assignment);
        } catch (DataIntegrityViolationException e) {
            // [CAUTION] 배정당 채팅방 유일성 충돌만 기존 방 조회로 복구
            if (!isDuplicateRoom(e)) {
                throw e;
            }

            // [NOTE] create의 트랜잭션이 롤백된 뒤 새 조회 트랜잭션에서 실행
            return chatRoomService
                    .findByAssignment(userId, assignment.getId())
                    .orElseThrow(() -> new ServiceException(ErrorCode.SERVER_ERROR));
        }
    }

    // [TOPIC] 배정당 채팅방 유일성 제약 위반 판별
    private boolean isDuplicateRoom(Throwable error) {
        Throwable cause = error;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uk_chat_rooms_user_reves".equals(violation.getConstraintName())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}