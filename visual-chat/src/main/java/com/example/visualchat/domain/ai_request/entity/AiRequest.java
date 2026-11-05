package com.example.visualchat.domain.ai_request.entity;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
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
import java.util.UUID;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "ai_requests",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_requests_chat_room_id",
                        columnNames = {"chat_room_id", "id"}
                ),
                @UniqueConstraint(
                        name = "uk_ai_requests_user_idempotency_key",
                        columnNames = {"user_id", "idempotency_key"}
                )
        },
        // [WHY] 엔티티 메서드를 우회한 변경에서도 상태와 정산 값의 일관성을 보장
        check = {
                @CheckConstraint(
                        name = "ck_ai_requests_vie_nonnegative",
                        constraint = "reserved_vie >= 0 and charged_vie >= 0"
                ),
                @CheckConstraint(
                        name = "ck_ai_requests_tokens_nonnegative",
                        constraint = """
                                (input_tokens is null or input_tokens >= 0)
                                and (output_tokens is null or output_tokens >= 0)
                                """
                ),
                @CheckConstraint(
                        name = "ck_ai_requests_charge_status",
                        constraint = "status = 'SUCCEEDED' or charged_vie = 0"
                ),
                @CheckConstraint(
                        name = "ck_ai_requests_completion",
                        constraint = """
                                (status = 'PROCESSING' and finished_at is null)
                                or (status in ('SUCCEEDED', 'FAILED') and finished_at is not null)
                                """
                ),
                @CheckConstraint(
                        name = "ck_ai_requests_time_order",
                        constraint = """
                                processing_deadline_at > started_at
                                and (finished_at is null or finished_at >= started_at)
                                """
                )
        }
)
public class AiRequest {
    private static final int VIE_PRECISION = 18;
    private static final int VIE_SCALE = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] user_id 저장은 chatRoom 연관관계가 담당하므로 회원 참조는 조회용으로 매핑
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] 회원과 채팅방을 함께 참조해 요청 회원의 채팅방만 연결되도록 함
    @JoinColumns(
            value = {
                    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false),
                    @JoinColumn(name = "chat_room_id", referencedColumnName = "id", nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_ai_requests_chat_room_owner")
    )
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // [WHY] 공유하는 user_id의 중복 쓰기를 피하면서 이용 주기의 소유권도 함께 참조
    @JoinColumns(
            value = {
                    @JoinColumn(
                            name = "user_id",
                            referencedColumnName = "user_id",
                            nullable = false,
                            insertable = false,
                            updatable = false
                    ),
                    @JoinColumn(
                            name = "usage_window_id",
                            referencedColumnName = "id",
                            nullable = false,
                            insertable = false,
                            updatable = false
                    )
            },
            foreignKey = @ForeignKey(name = "fk_ai_requests_usage_window_owner")
    )
    private AccountUsageWindow accountUsageWindow;

    // [WHY] 조회용 연관관계에서 쓰지 않는 usage_window_id의 저장을 담당
    @Column(name = "usage_window_id", nullable = false)
    private Long usageWindowId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "char(64)")
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private AiRequestStatus status;

    @Column(length = 64, nullable = false)
    private String provider;

    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;

    @Column(name = "provider_request_id")
    private String providerRequestId;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "metering_version", length = 64)
    private String meteringVersion;

    @Column(name = "reserved_vie", nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedVie;

    @Column(name = "charged_vie", nullable = false, precision = 18, scale = 6)
    private BigDecimal chargedVie;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "processing_deadline_at", nullable = false)
    private Instant processingDeadlineAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private AiRequest(
            User user,
            ChatRoom chatRoom,
            AccountUsageWindow accountUsageWindow,
            String idempotencyKey,
            String requestFingerprint,
            String provider,
            String modelName,
            BigDecimal reservedVie,
            Instant processingDeadlineAt
    ) {
        if (user == null || user.getId() == null || chatRoom == null || chatRoom.getId() == null
                || accountUsageWindow == null || accountUsageWindow.getId() == null) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "저장된 회원, 채팅방, 이용 주기가 필요합니다.");
        }

        // [WHY] 메모리에서도 요청 회원과 연결 대상의 소유권이 일치하도록 검증
        if (!user.getId().equals(chatRoom.getUser().getId())
                || !user.getId().equals(accountUsageWindow.getUser().getId())) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "채팅방과 이용 주기는 요청 회원의 소유여야 합니다.");
        }

        // [WHY] DB의 마이크로초 정밀도에 맞춰 저장 전후의 시각 비교가 일치하도록 함
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = processingDeadlineAt == null ? null : processingDeadlineAt.truncatedTo(ChronoUnit.MICROS);
        if (deadline == null || !deadline.isAfter(start)) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "처리 기한은 요청 시작 시각보다 뒤여야 합니다.");
        }

        // [WHY] 멱등키는 빈 문자열이나 공백만으로 구성될 수 없음
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "멱등키는 공백이 아닌 값이어야 합니다.");
        }

        BigDecimal reservation = validateVie(reservedVie, "예약량");
        this.user = user;
        this.chatRoom = chatRoom;
        this.accountUsageWindow = accountUsageWindow;
        this.usageWindowId = accountUsageWindow.getId();
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = AiRequestStatus.PROCESSING;
        this.provider = provider;
        this.modelName = modelName;
        this.reservedVie = reservation;
        this.chargedVie = BigDecimal.ZERO; // [NOTE] 요청 생성 시에는 확정 차감이 발생하지 않음
        this.startedAt = start;
        this.processingDeadlineAt = deadline;
    }

    public static AiRequest of(
            User user,
            ChatRoom chatRoom,
            AccountUsageWindow accountUsageWindow,
            String idempotencyKey,
            String requestFingerprint,
            String provider,
            String modelName,
            BigDecimal reservedVie,
            Instant processingDeadlineAt
    ) {
        return new AiRequest(user, chatRoom, accountUsageWindow, idempotencyKey, requestFingerprint,
                provider, modelName, reservedVie, processingDeadlineAt);
    }

    public void success(BigDecimal chargedVie) {
        BigDecimal charge = validateVie(chargedVie, "차감량");
        changeStatus(AiRequestStatus.SUCCEEDED);
        this.chargedVie = charge;
        this.finishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void fail(ErrorCode errorCode) {
        if (errorCode == null) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "실패 원인 코드가 필요합니다.");
        }
        changeStatus(AiRequestStatus.FAILED);
        this.chargedVie = BigDecimal.ZERO; // [WHY] 실패한 요청에는 이용량을 차감하지 않음
        this.errorCode = errorCode.getCode(); // [WHY] 외부 예외 원문 대신 프로젝트에서 정의한 오류 코드를 기록
        this.finishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // [WHY] DB 저장 시 반올림 또는 범위 초과로 정산 금액이 달라지지 않도록 검증
    private static BigDecimal validateVie(BigDecimal vie, String fieldName) {
        if (vie == null || vie.signum() < 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "은 0 이상이어야 합니다.");
        }

        BigDecimal normalized;
        try {
            normalized = vie.setScale(VIE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "은 소수점 6자리까지 입력할 수 있습니다.");
        }
        if (normalized.precision() > VIE_PRECISION) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, fieldName + "이 저장 가능한 VIE 범위를 초과했습니다.");
        }
        return normalized;
    }

    private void changeStatus(AiRequestStatus newStatus) {
        if (!this.status.canTransitTo(newStatus)) {
            throw new ServiceException(ErrorCode.INVALID_AI_REQUEST_STATUS);
        }
        this.status = newStatus;
    }
}
