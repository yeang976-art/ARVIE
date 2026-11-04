package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "user_reves",
        uniqueConstraints = {
                // [WHY] 첫 MVP에서는 회원당 레브 배정을 최대 1건으로 제한
                @UniqueConstraint(name = "uk_user_reves_user", columnNames = "user_id"),
                // [WHY] 채팅방에서 회원과 배정 기록의 소유권을 함께 참조하기 위해 선언
                @UniqueConstraint(name = "uk_user_reves_user_id", columnNames = {"user_id", "id"})
        }
)
public class UserReve {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reve_id", nullable = false)
    private Reve reve;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public UserReve(User user, Reve reve) {
        this.user = user;
        this.reve = reve;
        this.assignedAt = Instant.now();
    }

    public static UserReve of(User user, Reve reve) {
        return new UserReve(user, reve);
    }
}
