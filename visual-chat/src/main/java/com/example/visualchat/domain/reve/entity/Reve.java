package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.enums.Mbti;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * [ROLE] 레브의 기본 정보·시스템 프롬프트·체험 배정 설정을 관리
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "reves",
        uniqueConstraints = @UniqueConstraint(name = "uk_reves_code", columnNames = "code"),
        check = @CheckConstraint(
                name = "ck_reves_active_trial_weight",
                constraint = "not trial_enabled or trial_weight > 0"
        )
)
public class Reve extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String code;

    @Column(length = 100, nullable = false)
    private String name;

    // [NOTE] 성격 유형이 정해지지 않은 레브는 null을 허용
    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private Mbti mbti;

    // [NOTE] columnDefinition: 테이블 생성 시 사용할 DB 타입을 명시
    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "trial_enabled", nullable = false)
    private Boolean trialEnabled;

    // [WHY] 소수 단위의 상대 가중치를 정확하게 저장하기 위해
    @Column(name = "trial_weight", nullable = false, precision = 12, scale = 6)
    private BigDecimal trialWeight;

    private Reve(
            String code,
            String name,
            Mbti mbti,
            String description,
            String systemPrompt,
            BigDecimal trialWeight
    ) {
        if (trialWeight == null || trialWeight.signum() <= 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "체험 배정 가중치는 0보다 커야 합니다.");
        }

        this.code = code;
        this.name = name;
        this.mbti = mbti;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.trialWeight = trialWeight;
        this.trialEnabled = true;
    }

    public static Reve of(
            String code,
            String name,
            Mbti mbti,
            String description,
            String systemPrompt,
            BigDecimal trialWeight
    ) {
        return new Reve(code, name, mbti, description, systemPrompt, trialWeight);
    }

    public void exceptTrial() {
        this.trialEnabled = false;
    }
}
