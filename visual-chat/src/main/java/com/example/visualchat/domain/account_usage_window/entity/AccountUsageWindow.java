package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "account_usage_windows",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_account_usage_windows_user_period_start",
                        columnNames = {"user_id", "period_start_at"}
                ),
                @UniqueConstraint(
                        name = "uk_account_usage_windows_user_id",
                        columnNames = {"user_id", "id"}
                )
        },
        // [WHY] 직접 SQL 등 엔티티 메서드를 거치지 않는 변경에서도 수량과 기간의 유효성을 보장
        check = {
                @CheckConstraint(
                        name = "ck_account_usage_windows_vie_nonnegative",
                        constraint = "allowance_vie >= 0 and used_vie >= 0 and reserved_vie >= 0"
                ),
                @CheckConstraint(
                        name = "ck_account_usage_windows_vie_limit",
                        constraint = "used_vie + reserved_vie <= allowance_vie"
                ),
                @CheckConstraint(
                        name = "ck_account_usage_windows_period_order",
                        constraint = "period_start_at < period_end_at"
                )
        }
)
public class AccountUsageWindow extends BaseTimeEntity {
    // [NOTE] 이용 주기는 달력상의 한 달이 아닌 고정 30일
    private static final long PERIOD_DAYS = 30L;

    private static final long INITIAL_VIE = 20_000L;
    private static final int VIE_SCALE = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "period_start_at", nullable = false)
    private Instant periodStartAt;

    @Column(name = "period_end_at", nullable = false)
    private Instant periodEndAt;

    @Column(name = "allowance_vie", nullable = false, precision = 18, scale = 6)
    private BigDecimal allowanceVie;

    @Column(name = "used_vie", nullable = false, precision = 18, scale = 6)
    private BigDecimal usedVie;

    @Column(name = "reserved_vie", nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedVie;

    @Version
    @Column(nullable = false)
    private Long version;

    private AccountUsageWindow(User user) {
        this.user = user;
        this.periodStartAt = user.getSignupCompletedAt();
        this.periodEndAt = this.periodStartAt.plus(PERIOD_DAYS, ChronoUnit.DAYS);
        this.allowanceVie = BigDecimal.valueOf(INITIAL_VIE);
        this.usedVie = this.reservedVie = BigDecimal.ZERO;
    }

    // [NOTE] 첫 이용 주기는 가입 완료 시각을 기준으로 시작
    public static AccountUsageWindow of(User user) {
        return new AccountUsageWindow(user);
    }

    // [WHY] 제공량은 주기 전체의 한도이므로 유지하고, 사용량과 활성 예약량을 제외해 잔여량을 계산
    public BigDecimal getRemainingVie() {
        return allowanceVie.subtract(usedVie).subtract(reservedVie);
    }

    public void reserveVie(BigDecimal vie) {
        if (vie == null || vie.signum() < 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "예약량은 0 이상이어야 합니다.");
        }

        // [WHY] DB 저장 시 반올림되어 예약량이 바뀌지 않도록 값 손실이 있는 입력은 거절한다.
        BigDecimal reservation;
        try {
            reservation = vie.setScale(VIE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "예약량은 소수점 6자리까지 입력할 수 있습니다.");
        }

        // [WHY] 이미 예약된 VIE도 제외한 잔여량을 기준으로 추가 예약 가능 여부를 판단
        if (reservation.compareTo(getRemainingVie()) > 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "예약 가능한 VIE가 부족합니다.");
        }
        // [WHY] 다른 진행 중인 요청의 예약량을 보존하기 위해 누적
        this.reservedVie = this.reservedVie.add(reservation);
    }
}
