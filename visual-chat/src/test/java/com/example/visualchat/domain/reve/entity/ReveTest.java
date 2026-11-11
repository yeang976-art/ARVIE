package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.enums.Mbti;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ReveTest {

    @Test
    @DisplayName("새 레브는 전달받은 원본 설정을 보관하고 체험 배정 대상으로 생성된다")
    void createsActiveTrialReve() {
        BigDecimal weight = new BigDecimal("1.250000");

        Reve reve = Reve.of("REVE_001", "루미", Mbti.INFP, "레브 소개", "서버 전용 설정", weight);

        assertThat(reve.getId()).isNull();
        assertThat(reve.getCode()).isEqualTo("REVE_001");
        assertThat(reve.getName()).isEqualTo("루미");
        assertThat(reve.getMbti()).isEqualTo(Mbti.INFP);
        assertThat(reve.getDescription()).isEqualTo("레브 소개");
        assertThat(reve.getSystemPrompt()).isEqualTo("서버 전용 설정");
        assertThat(reve.getTrialEnabled()).isTrue();
        assertThat(reve.getTrialWeight()).isEqualByComparingTo(weight);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.000001", "0.25", "1.000000", "999999.999999"})
    @DisplayName("양수인 소수 가중치와 저장 범위의 경계값을 사용할 수 있다")
    void acceptsPositiveTrialWeights(String value) {
        BigDecimal weight = new BigDecimal(value);

        Reve reve = Reve.of("REVE_001", "루미", Mbti.INFP, "소개", "설정", weight);

        assertThat(reve.getTrialEnabled()).isTrue();
        assertThat(reve.getTrialWeight()).isEqualByComparingTo(weight);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.000000", "-1", "-0.000001"})
    @DisplayName("체험 배정 대상은 null이나 0 이하의 가중치로 생성할 수 없다")
    void rejectsMissingOrNonPositiveTrialWeights(String value) {
        BigDecimal weight = value == null ? null : new BigDecimal(value);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> Reve.of("REVE_001", "루미", Mbti.INFP, "소개", "설정", weight))
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("체험 대상에서 반복 제외해도 원본 설정과 가중치는 유지된다")
    void excludesFromTrialWithoutChangingOriginalSettings() {
        Reve reve = Reve.of("REVE_001", "루미", Mbti.INFP, "소개", "설정", new BigDecimal("1.25"));

        reve.exceptTrial();
        reve.exceptTrial();

        assertThat(reve.getTrialEnabled()).isFalse();
        assertThat(reve.getMbti()).isEqualTo(Mbti.INFP);
        assertThat(reve.getCode()).isEqualTo("REVE_001");
        assertThat(reve.getName()).isEqualTo("루미");
        assertThat(reve.getDescription()).isEqualTo("소개");
        assertThat(reve.getSystemPrompt()).isEqualTo("설정");
        assertThat(reve.getTrialWeight()).isEqualByComparingTo("1.25");
    }
}
