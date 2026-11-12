package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowService;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.metering.TerraVieCalculator;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** [ROLE] AI 요청 완료와 계정 VIE 정산을 하나의 트랜잭션으로 조율 */
@Service
@RequiredArgsConstructor
public class AiRequestCommand {

    private final ChatRoomService chatRoomService;
    private final TerraVieCalculator vieCalculator;
    private final AiRequestService aiRequestService;
    private final AccountUsageWindowService usageWindowService;

    // [TOPIC] 예약 당시 정책과 실제 토큰 사용량에 따른 성공 정산
    @Transactional
    public void completeSuccess(Long userId, UUID requestId, AiTokenUsage usage) {
        AiRequest request = aiRequestService.getOwnedRequest(userId, requestId);

        // [CAUTION] 지원하지 않는 모델이나 과거 정책을 현재 요율로 임의 정산하지 않음
        if (!"OPENAI".equals(request.getProvider())
                || !TerraVieCalculator.MODEL_NAME.equals(request.getModelName())
                || !TerraVieCalculator.VERSION.equals(request.getMeteringVersion())) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        BigDecimal chargedVie = vieCalculator.calculate(usage);
        request.success(usage, chargedVie);

        AccountUsageWindow window = usageWindowService.getOwnedWindow(userId, request.getUsageWindowId());

        window.settleVie(request.getReservedVie(), request.getChargedVie());
    }

    // [TOPIC] 실패가 확정된 요청의 예약 해제
    @Transactional
    public void completeFailure(Long userId, UUID requestId, ErrorCode errorCode) {
        AiRequest request = aiRequestService.getOwnedRequest(userId, requestId);

        // [WHY] 처리 중인 요청만 실패로 전환하고 최종 차감량을 0으로 기록
        request.fail(errorCode);

        AccountUsageWindow window = usageWindowService.getOwnedWindow(userId, request.getUsageWindowId());

        window.releaseVie(request.getReservedVie());
    }

    // [TOPIC] 토큰 상한에 따른 VIE 예약과 AI 요청 생성
    @Transactional
    public UUID reserve(
            Long userId,
            Long roomId,
            Long usageWindowId,
            String idempotencyKey,
            String requestFingerprint,
            long inputTokenUpperBound,
            long maxOutputTokens,
            Instant processingDeadlineAt
    ) {
        BigDecimal reservedVie = vieCalculator.estimateReservation(inputTokenUpperBound, maxOutputTokens);

        ChatRoom chatRoom = chatRoomService.getOwnedRoom(userId, roomId);
        AccountUsageWindow window = usageWindowService.getOwnedWindow(userId, usageWindowId);

        AiRequest request = AiRequest.of(
                window.getUser(),
                chatRoom,
                window,
                idempotencyKey,
                requestFingerprint,
                "OPENAI",
                TerraVieCalculator.MODEL_NAME,
                TerraVieCalculator.VERSION,
                reservedVie,
                processingDeadlineAt
        );

        Instant startedAt = request.getStartedAt();

        if (startedAt.isBefore(window.getPeriodStartAt()) || !startedAt.isBefore(window.getPeriodEndAt())) {
            throw new ServiceException(ErrorCode.USAGE_PERIOD_CHANGED);
        }

        window.reserveVie(request.getReservedVie());

        return aiRequestService.create(request).getId();
    }
}