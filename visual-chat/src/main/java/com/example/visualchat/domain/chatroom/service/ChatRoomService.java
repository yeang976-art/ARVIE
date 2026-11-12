package com.example.visualchat.domain.chatroom.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.chatroom.dto.ChatRoomResponse;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.repository.ChatRoomRepository;
import com.example.visualchat.domain.reve.entity.UserReve;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** [ROLE] 채팅방의 생성과 회원 소유권을 기준으로 한 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;

    // [TOPIC] 본인 배정에 연결된 기존 채팅방 조회
    public Optional<ChatRoomResponse> findByAssignment(Long userId, Long userReveId) {
        return chatRoomRepository
                .findByUser_IdAndUserReve_Id(userId, userReveId)
                .map(ChatRoomResponse::from);
    }

    // [TOPIC] 소유권 확인이 완료된 배정으로 채팅방 생성
    @Transactional
    public ChatRoomResponse create(UserReve userReve) {
        ChatRoom chatRoom = ChatRoom.of(userReve);

        // [WHY] 저장 단계에서 유일성 충돌을 확인하고 예외를 호출자에게 전달
        ChatRoom saved = chatRoomRepository.saveAndFlush(chatRoom);

        return ChatRoomResponse.from(saved);
    }

    // [TOPIC] 본인 소유 채팅방의 보관 설정 조회
    public ChatRoomResponse getSettings(Long userId, Long roomId) {
        ChatRoom chatRoom = chatRoomRepository
                .findByIdAndUser_Id(roomId, userId)
                .orElseThrow(() -> new ServiceException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        return ChatRoomResponse.from(chatRoom);
    }
}