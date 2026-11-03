package com.example.visualchat.domain.user.entity;

import com.example.visualchat.common.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("회원 저장 후 다시 조회해도 가입 정보와 감사 시각이 유지된다")
    void persistsUserAndAuditingTimestamps() {
        User user = User.of("arvie", "user@example.com", "encoded-password");
        entityManager.persist(user);
        entityManager.flush();
        Long userId = user.getId();
        entityManager.clear();

        User savedUser = entityManager.find(User.class, userId);

        assertThat(userId).isNotNull();
        assertThat(savedUser.getNickname()).isEqualTo("arvie");
        assertThat(savedUser.getEmail()).isEqualTo("user@example.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedUser.getSignupCompletedAt()).isNotNull();
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(savedUser.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("닉네임이 달라도 같은 이메일로는 회원을 중복 저장할 수 없다")
    void rejectsDuplicateEmail() {
        entityManager.persist(User.of("first", "same@example.com", "encoded-password"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(User.of("second", "same@example.com", "encoded-password"));
            entityManager.flush();
        }).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    @DisplayName("이메일이 달라도 같은 닉네임으로는 회원을 중복 저장할 수 없다")
    void rejectsDuplicateNickname() {
        entityManager.persist(User.of("same", "first@example.com", "encoded-password"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(User.of("same", "second@example.com", "encoded-password"));
            entityManager.flush();
        }).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @ParameterizedTest(name = "nickname={0}, email={1}, passwordHash={2}")
    @CsvSource(value = {
            "NULL,user@example.com,encoded-password",
            "arvie,NULL,encoded-password",
            "arvie,user@example.com,NULL"
    }, nullValues = "NULL")
    @DisplayName("필수 회원 정보가 없으면 저장을 거절한다")
    void rejectsMissingRequiredInformation(String nickname, String email, String passwordHash) {
        assertThatThrownBy(() -> {
            entityManager.persist(User.of(nickname, email, passwordHash));
            entityManager.flush();
        }).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }
}
