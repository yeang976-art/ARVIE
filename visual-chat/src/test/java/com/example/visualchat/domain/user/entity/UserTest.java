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

    @Test
    @DisplayName("정적 팩터리는 이메일 도메인을 정규화하고 로컬 부분의 대소문자를 보존한다")
    void factoryNormalizesEmailDomain() {
        User user = User.of("arvie", "User@EXAMPLE.COM", "encoded-password");

        assertThat(user.getEmail()).isEqualTo("User@example.com");
    }

    @Test
    @DisplayName("빌더 생성도 정적 팩터리와 같은 이메일 정규화 규칙을 적용한다")
    void builderNormalizesEmailDomain() {
        User user = User.builder()
                .nickname("arvie")
                .email("User@EXAMPLE.COM")
                .passwordHash("encoded-password")
                .build();

        assertThat(user.getEmail()).isEqualTo("User@example.com");
    }
    private static void assertNewUser(User user, Instant beforeCreation, Instant afterCreation) {
        assertThat(user.getId()).isNull();
        assertThat(user.getNickname()).isEqualTo("arvie");
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(user.getSignupCompletedAt()).isBetween(beforeCreation, afterCreation);
    }
}
