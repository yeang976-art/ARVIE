package com.example.visualchat.domain.chatroom.dto;

import com.example.visualchat.domain.chatroom.entity.ChatRoom;

/** [ROLE] 채팅방 식별값과 메시지 보관 설정을 전달 */
public record ChatRoomResponse(Long roomId, Short retentionDays) {

    public static ChatRoomResponse from(ChatRoom chatRoom) {
        return new ChatRoomResponse(chatRoom.getId(), chatRoom.getRetentionDays());
    }
}