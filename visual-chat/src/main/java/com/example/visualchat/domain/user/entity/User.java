package com.example.visualchat.domain.user.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.domain.user.util.EmailNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** [ROLE] 회원의 식별 정보·비밀번호 해시·가입 완료 시각을 관리 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "users",
        // 유일성 제약의 이름을 명시적으로 관리한다.
        // 여러 컬럼 조합의 유일성이 필요한 경우에도 같은 방식으로 선언할 수 있다.
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_users_nickname", columnNames = "nickname")
        }
)
public class User extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "signup_completed_at", nullable = false)
    private Instant signupCompletedAt;

    @Builder
    private User(String nickname, String email, String passwordHash) {
        this.nickname = nickname;
        this.email = EmailNormalizer.normalizeDomain(email);
        this.passwordHash = passwordHash;
        this.signupCompletedAt = Instant.now();
    }

    public static User of(String nickname, String email, String passwordHash) {
        return new User(nickname, email, passwordHash);
    }
}
