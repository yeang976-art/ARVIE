package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.common.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class ReveJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("긴 소개와 시스템 프롬프트, 소수 가중치, 감사 시각을 저장하고 다시 조회한다")
    void persistsOriginalSettingsAndAuditingTimestamps() {
        String description = "레브 소개 ".repeat(100);
        String systemPrompt = "서버 전용 성격과 말투 설정 ".repeat(100);
        Reve reve = Reve.of("REVE_001", "루미", description, systemPrompt, new BigDecimal("12.345678"));
        entityManager.persist(reve);
        entityManager.flush();
        Long id = reve.getId();
        Instant createdAt = reve.getCreatedAt();
        Instant updatedAt = reve.getUpdatedAt();
        entityManager.clear();

        Reve savedReve = entityManager.find(Reve.class, id);

        assertThat(id).isNotNull();
        assertThat(savedReve.getCode()).isEqualTo("REVE_001");
        assertThat(savedReve.getName()).isEqualTo("루미");
        assertThat(savedReve.getDescription()).isEqualTo(description);
        assertThat(savedReve.getSystemPrompt()).isEqualTo(systemPrompt);
        assertThat(savedReve.getTrialEnabled()).isTrue();
        assertThat(savedReve.getTrialWeight()).isEqualByComparingTo("12.345678");
        assertThat(savedReve.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(savedReve.getUpdatedAt()).isCloseTo(updatedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("이름이 달라도 서버 코드가 같으면 중복 저장을 거절한다")
    void rejectsDuplicateServerCode() {
        entityManager.persist(Reve.of("REVE_001", "루미", "소개", "설정", BigDecimal.ONE));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(Reve.of("REVE_001", "다른 레브", "다른 소개", "다른 설정", BigDecimal.ONE));
                    entityManager.flush();
                })
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("코드 64자, 이름 100자, 가중치 numeric(12,6)의 최댓값을 저장할 수 있다")
    void acceptsMaximumColumnValues() {
        Reve reve = Reve.of("R".repeat(64), "가".repeat(100), "소개", "설정", new BigDecimal("999999.999999"));
        entityManager.persist(reve);
        entityManager.flush();
        entityManager.clear();

        Reve savedReve = entityManager.find(Reve.class, reve.getId());

        assertThat(savedReve.getCode()).hasSize(64);
        assertThat(savedReve.getName()).hasSize(100);
        assertThat(savedReve.getTrialWeight()).isEqualByComparingTo("999999.999999");
    }

    @ParameterizedTest
    @CsvSource({"65,100", "64,101"})
    @DisplayName("코드 또는 이름의 최대 길이를 넘으면 DB 저장을 거절한다")
    void rejectsValuesLongerThanColumnLimits(int codeLength, int nameLength) {
        assertThatExceptionOfType(DataException.class)
                .isThrownBy(() -> {
                    entityManager.persist(Reve.of("R".repeat(codeLength), "가".repeat(nameLength), "소개", "설정", BigDecimal.ONE));
                    entityManager.flush();
                })
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("22001"));
    }

    @Test
    @DisplayName("가중치의 정수부가 numeric(12,6)의 범위를 넘으면 DB 저장을 거절한다")
    void rejectsWeightOutsideNumericPrecision() {
        assertThatExceptionOfType(DataException.class)
                .isThrownBy(() -> {
                    entityManager.persist(Reve.of("REVE_001", "루미", "소개", "설정", new BigDecimal("1000000")));
                    entityManager.flush();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0000001"})
    @DisplayName("DB도 활성 레브의 0 이하 가중치와 저장 시 0으로 반올림되는 가중치를 거절한다")
    void rejectsNonPositiveStoredTrialWeight(String value) {
        // [WHY] 생성자 검증을 우회해 실제 DB CHECK 제약이 규칙을 보장하는지 확인한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("""
                        insert into reves
                            (code, name, description, system_prompt, trial_enabled, trial_weight, created_at, updated_at)
                        values
                            ('REVE_001', '루미', '소개', '설정', true, :weight, current_timestamp, current_timestamp)
                        """)
                        .setParameter("weight", new BigDecimal(value))
                        .executeUpdate())
                .satisfies(exception -> assertThat(exception.getConstraintName())
                        .containsIgnoringCase("ck_reves_active_trial_weight"));
    }

    @Test
    @DisplayName("체험 배정 제외 상태는 저장 후 다시 조회해도 유지된다")
    void persistsTrialExclusion() {
        Reve reve = Reve.of("REVE_001", "루미", "소개", "설정", new BigDecimal("1.25"));
        entityManager.persist(reve);
        entityManager.flush();

        reve.exceptTrial();
        entityManager.flush();
        entityManager.clear();

        Reve savedReve = entityManager.find(Reve.class, reve.getId());
        assertThat(savedReve.getTrialEnabled()).isFalse();
        assertThat(savedReve.getTrialWeight()).isEqualByComparingTo("1.25");
    }
}
