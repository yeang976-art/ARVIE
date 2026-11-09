package com.example.visualchat.domain.user.entity;

import com.example.visualchat.global.config.JpaAuditingConfig;
import com.example.visualchat.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일 조회는 닉네임이 다른 회원 중에서도 정확한 회원을 반환한다")
    void findsMemberByEmail() {
        entityManager.persist(User.of("first", "first@example.com", "first-hash"));
        User expected = User.of("second", "second@example.com", "second-hash");
        entityManager.persist(expected);
        entityManager.flush();
        Long expectedId = expected.getId();
        entityManager.clear();

        assertThat(userRepository.findByEmail("second@example.com"))
                .hasValueSatisfying(user -> {
                    assertThat(user.getId()).isEqualTo(expectedId);
                    assertThat(user.getPasswordHash()).isEqualTo("second-hash");
                });
    }

    @Test
    @DisplayName("등록되지 않은 이메일 조회는 빈 결과를 반환한다")
    void returnsEmptyForUnknownEmail() {
        assertThat(userRepository.findByEmail("missing@example.com")).isEmpty();
    }

    @Test
    @DisplayName("회원 저장 후 다시 조회해도 가입 정보와 감사 시각이 유지된다")
    void persistsUserAndAuditingTimestamps() {
        User user = User.of("arvie", "user@example.com", "encoded-password");
        entityManager.persist(user);
        entityManager.flush();
        Long userId = user.getId();
        Instant signupCompletedAt = user.getSignupCompletedAt();
        Instant createdAt = user.getCreatedAt();
        Instant updatedAt = user.getUpdatedAt();
        entityManager.clear();

        User savedUser = entityManager.find(User.class, userId);

        assertThat(userId).isNotNull();
        assertThat(savedUser.getNickname()).isEqualTo("arvie");
        assertThat(savedUser.getEmail()).isEqualTo("user@example.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedUser.getSignupCompletedAt()).isCloseTo(signupCompletedAt, within(1, ChronoUnit.MICROS));
        assertThat(savedUser.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(savedUser.getUpdatedAt()).isCloseTo(updatedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("닉네임이 달라도 같은 이메일로는 회원을 중복 저장할 수 없다")
    void rejectsDuplicateEmail() {
        entityManager.persist(User.of("first", "same@example.com", "encoded-password"));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(User.of("second", "same@example.com", "encoded-password"));
                    entityManager.flush();
                })
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("uk_users_email");
                });
    }

    @Test
    @DisplayName("이메일이 달라도 같은 닉네임으로는 회원을 중복 저장할 수 없다")
    void rejectsDuplicateNickname() {
        entityManager.persist(User.of("same", "first@example.com", "encoded-password"));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(User.of("same", "second@example.com", "encoded-password"));
                    entityManager.flush();
                })
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("uk_users_nickname");
                });
    }

    @ParameterizedTest(name = "필수 컬럼 누락: {3}")
    @CsvSource(value = {
            "NULL,user@example.com,encoded-password,nickname",
            "arvie,NULL,encoded-password,email",
            "arvie,user@example.com,NULL,password_hash"
    }, nullValues = "NULL")
    @DisplayName("필수 회원 정보가 없으면 저장을 거절한다")
    void rejectsMissingRequiredInformation(String nickname, String email, String passwordHash, String missingColumn) {
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(User.of(nickname, email, passwordHash));
                    entityManager.flush();
                })
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(missingColumn);
                });
    }

    @Test
    @DisplayName("닉네임 50자와 이메일 254자의 저장 경계값을 허용한다")
    void acceptsMaximumColumnLengths() {
        User user = User.of("가".repeat(50), "a".repeat(242) + "@example.com", "encoded-password");
        entityManager.persist(user);
        entityManager.flush();
        entityManager.clear();

        User saved = entityManager.find(User.class, user.getId());

        assertThat(saved.getNickname()).hasSize(50);
        assertThat(saved.getEmail()).hasSize(254);
    }

    @ParameterizedTest
    @CsvSource({"51,254", "50,255"})
    @DisplayName("닉네임 또는 이메일의 최대 길이를 넘으면 DB 저장을 거절한다")
    void rejectsValuesLongerThanColumnLimits(int nicknameLength, int emailLength) {
        String email = "a".repeat(emailLength - 12) + "@example.com";

        assertThatExceptionOfType(DataException.class)
                .isThrownBy(() -> {
                    entityManager.persist(User.of("가".repeat(nicknameLength), email, "encoded-password"));
                    entityManager.flush();
                })
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("22001"));
    }
}
