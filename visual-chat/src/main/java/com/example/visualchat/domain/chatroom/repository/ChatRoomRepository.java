package com.example.visualchat.domain.chatroom.repository;

import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** [ROLE] 채팅방 저장과 회원 소유권을 기준으로 한 조회를 담당 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // [TOPIC] 본인 배정에 연결된 기존 채팅방 조회
    Optional<ChatRoom> findByUser_IdAndUserReve_Id(Long userId, Long userReveId);

    // [TOPIC] 본인 소유 채팅방 조회
    Optional<ChatRoom> findByIdAndUser_Id(Long roomId, Long userId);
}