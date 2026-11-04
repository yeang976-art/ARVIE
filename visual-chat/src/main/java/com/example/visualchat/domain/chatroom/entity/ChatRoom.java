package com.example.visualchat.domain.chatroom.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_rooms",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_rooms_user_reves", columnNames = "user_reve_id"),
                @UniqueConstraint(name = "uk_chat_rooms_user_id", columnNames = {"user_id", "id"}),
        },
        check = @CheckConstraint(
                name = "ck_chat_rooms_retention_days",
                constraint = "retention_days between 1 and 180"
        )
)
public class ChatRoom extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] user_id 저장은 userReve 연관관계에서 담당하므로 회원 참조는 조회용으로 매핑
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] 회원과 배정 기록을 함께 참조해 다른 회원의 배정 기록이 연결되는 것을 막음
    @JoinColumns(
            value = {
                    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false),
                    @JoinColumn(name = "user_reve_id", referencedColumnName = "id", nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_chat_rooms_user_reve_owner")
    )
    private UserReve userReve;

    // [NOTE] 채팅 보관기간
    @Column(name = "retention_days", nullable = false)
    private Short retentionDays;

    @Version
    @Column(nullable = false)
    private Long version;

    private ChatRoom(UserReve userReve) {
        this.user = userReve.getUser();
        this.userReve = userReve;
        this.retentionDays = 90;
    }

    public static ChatRoom of(UserReve userReve) {
        return new ChatRoom(userReve);
    }

    public void modifyRetentionDays(Short days) {
        if (days == null || days < 1 || days > 180) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "보관 기간은 1일부터 180일까지 설정할 수 있습니다.");
        }
        this.retentionDays = days;
    }
}
