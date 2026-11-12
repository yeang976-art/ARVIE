package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.entity.BaseTimeEntity;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
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

/** [ROLE] 계정의 이용 기간과 VIE 제공량·사용량·활성 예약량을 관리 */
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
    private static final int VIE_PRECISION = 18;

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

    // [TOPIC] 가입 기준에 맞는 이용 주기와 초기 VIE 생성
    private AccountUsageWindow(User user, UsagePeriod period) {
        if (user == null || period == null) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        // [WHY] 직접 생성한 UsagePeriod도 가입 기준의 정확한 30일 구간인지 확인
        UsagePeriod expected = UsagePeriod.from(user.getSignupCompletedAt(), period.startAt());

        if (!expected.equals(period)) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        this.user = user;
        this.periodStartAt = period.startAt();
        this.periodEndAt = period.endAt();
        this.allowanceVie = BigDecimal.valueOf(INITIAL_VIE);
        this.usedVie = BigDecimal.ZERO;
        this.reservedVie = BigDecimal.ZERO;
    }

    // [TOPIC] 가입 시 첫 이용 주기 생성
    public static AccountUsageWindow of(User user) {
        UsagePeriod initialPeriod = UsagePeriod.from(user.getSignupCompletedAt(), user.getSignupCompletedAt());

        return new AccountUsageWindow(user, initialPeriod);
    }

    // [TOPIC] 계산된 이용 주기로 새 행 생성
    public static AccountUsageWindow of(User user, UsagePeriod period) {
        return new AccountUsageWindow(user, period);
    }

    // [TOPIC] 잔여 VIE 조회와 추가 이용량 예약
    // [WHY] 제공량은 주기 전체의 한도이므로 유지하고, 사용량과 활성 예약량을 제외해 잔여량을 계산
    public BigDecimal getRemainingVie() {
        return allowanceVie.subtract(usedVie).subtract(reservedVie);
    }

    // [TOPIC] 추가 이용량 예약
    public void reserveVie(BigDecimal vie) {
        BigDecimal reservation = validateVie(vie, "예약량");

        // [WHY] 진행 중인 다른 요청의 예약량도 제외한 잔여량으로 판단
        if (reservation.compareTo(getRemainingVie()) > 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "예약 가능한 VIE가 부족합니다.");
        }

        this.reservedVie = this.reservedVie.add(reservation);
    }

    // [TOPIC] 성공한 요청의 예약 해제와 실제 사용량 반영
    public void settleVie(BigDecimal reservation, BigDecimal charge) {
        BigDecimal normalizedReservation = validateVie(reservation, "예약량");
        BigDecimal normalizedCharge = validateVie(charge, "차감량");

        validateReservedBalance(normalizedReservation);

        // [CAUTION] 예약 상한을 초과한 결과는 임의로 잘라 차감하지 않고 정산을 중단
        if (normalizedCharge.compareTo(normalizedReservation) > 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "차감량은 해당 요청의 예약량을 초과할 수 없습니다.");
        }

        BigDecimal nextReservedVie = this.reservedVie.subtract(normalizedReservation);
        BigDecimal nextUsedVie = this.usedVie.add(normalizedCharge);

        // [WHY] 모든 검증을 마친 뒤 함께 변경해 검증 실패 시 기존 잔액을 유지
        this.reservedVie = nextReservedVie;
        this.usedVie = nextUsedVie;
    }

    // [TOPIC] 실패가 확정된 요청의 예약 해제
    public void releaseVie(BigDecimal reservation) {
        BigDecimal normalizedReservation = validateVie(reservation, "예약량");

        validateReservedBalance(normalizedReservation);

        // [WHY] 실패한 요청은 실제 사용량을 늘리지 않고 해당 예약량만 해제
        this.reservedVie = this.reservedVie.subtract(normalizedReservation);
    }

    // [TOPIC] 활성 예약 잔액 검증
    private void validateReservedBalance(BigDecimal reservation) {
        if (reservation.compareTo(this.reservedVie) > 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "해제할 예약량이 활성 예약 잔액을 초과했습니다.");
        }
    }

    // [TOPIC] VIE 수량과 저장 정밀도 검증
    private static BigDecimal validateVie(BigDecimal vie, String fieldName) {
        if (vie == null || vie.signum() < 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "은 0 이상이어야 합니다.");
        }

        BigDecimal normalized;

        try {
            // [WHY] 엔티티에서는 환산 값을 반올림하지 않고 값 손실 여부를 검증
            normalized = vie.setScale(VIE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "은 소수점 6자리까지 입력할 수 있습니다.");
        }

        if (normalized.precision() > VIE_PRECISION) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "이 저장 가능한 VIE 범위를 초과했습니다.");
        }

        return normalized;
    }
}
