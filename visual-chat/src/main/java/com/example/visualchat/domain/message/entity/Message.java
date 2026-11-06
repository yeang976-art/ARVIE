package com.example.visualchat.domain.message.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.message.enums.SenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_messages_ai_request_sender",
                columnNames = {"ai_request_id", "sender"}
        ),
        indexes = {
                // [WHY] 채팅방별 메시지를 보낸 시각과 ID 순서로 조회하기 위해 사용
                @Index(name = "idx_messages_chat_room_sent_at_id", columnList = "chat_room_id, sent_at, id"),
                // [WHY] 만료된 메시지를 정리할 때 삭제 대상을 빠르게 찾기 위해 사용
                @Index(name = "idx_messages_expires_at", columnList = "expires_at")
        }
)
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] chat_room_id 저장은 aiRequest 연관관계가 담당
    @JoinColumn(name = "chat_room_id", nullable = false, insertable = false, updatable = false)
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] 메시지가 속한 채팅방과 AI 요청의 채팅방이 일치하도록 함께 참조
    // [WHY] 메시지를 삭제해도 요청 기록과 다른 메시지를 보존하도록 삭제 전파를 설정하지 않음
    @JoinColumns(
            value = {
                    @JoinColumn(name = "chat_room_id", referencedColumnName = "chat_room_id", nullable = false),
                    @JoinColumn(name = "ai_request_id", referencedColumnName = "id", nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_messages_ai_request_room")
    )
    private AiRequest aiRequest;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private SenderType sender;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    private Message(ChatRoom chatRoom, AiRequest aiRequest, SenderType sender, String content) {
        if (chatRoom == null || chatRoom.getId() == null || aiRequest == null || aiRequest.getId() == null) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "저장된 채팅방과 AI 요청이 필요합니다.");
        }

        // [WHY] 실제 저장될 요청의 채팅방과 보관 기간 계산에 사용하는 채팅방이 일치하도록 검증
        if (!chatRoom.getId().equals(aiRequest.getChatRoom().getId())) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "AI 요청과 메시지는 같은 채팅방에 속해야 합니다.");
        }

        this.chatRoom = chatRoom;
        this.aiRequest = aiRequest;
        this.sender = sender;
        this.content = content;
        this.sentAt = Instant.now();

        // [NOTE] 메시지를 보낸 시각부터 채팅방의 보관 기간이 지나면 만료
        this.expiresAt = this.sentAt.plus(chatRoom.getRetentionDays(), ChronoUnit.DAYS);
    }

    public static Message of(ChatRoom chatRoom, AiRequest aiRequest, SenderType sender, String content) {
        return new Message(chatRoom, aiRequest, sender, content);
    }
}
