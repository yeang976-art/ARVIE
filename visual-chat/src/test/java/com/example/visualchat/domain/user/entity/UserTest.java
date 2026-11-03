package com.example.visualchat.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    @DisplayName("정적 팩터리로 회원을 생성하면 가입 완료 시각이 초기화된다")
    void createsUserWithSignupCompletedAt() {
        Instant beforeCreation = Instant.now();

        User user = User.of("arvie", "user@example.com", "encoded-password");

        Instant afterCreation = Instant.now();
        assertNewUser(user, beforeCreation, afterCreation);
    }

    @Test
    @DisplayName("빌더로 생성해도 가입 완료 시각과 전달받은 비밀번호 해시가 유지된다")
    void builderPreservesSignupInvariant() {
        Instant beforeCreation = Instant.now();

        User user = User.builder()
                .nickname("arvie")
                .email("user@example.com")
                .passwordHash("encoded-password")
                .build();

        Instant afterCreation = Instant.now();
        assertNewUser(user, beforeCreation, afterCreation);
    }

    private static void assertNewUser(User user, Instant beforeCreation, Instant afterCreation) {
        assertThat(user.getId()).isNull();
        assertThat(user.getNickname()).isEqualTo("arvie");
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(user.getSignupCompletedAt()).isBetween(beforeCreation, afterCreation);
    }
}
