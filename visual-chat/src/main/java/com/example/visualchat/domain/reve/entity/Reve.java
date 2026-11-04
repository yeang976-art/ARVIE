package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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

    private Reve(String code, String name, String description, String systemPrompt, BigDecimal trialWeight) {
        if (trialWeight == null || trialWeight.signum() <= 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "체험 배정 가중치는 0보다 커야 합니다.");
        }

        this.code = code;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.trialWeight = trialWeight;
        this.trialEnabled = true;
    }

    public static Reve of(String code, String name, String description, String systemPrompt, BigDecimal trialWeight) {
        return new Reve(code, name, description, systemPrompt, trialWeight);
    }

    public void exceptTrial() {
        this.trialEnabled = false;
    }
}
