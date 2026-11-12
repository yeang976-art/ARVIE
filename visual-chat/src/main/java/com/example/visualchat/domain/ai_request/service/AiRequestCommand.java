package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowService;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
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
    private final AiRequestService aiRequestService;
    private final AccountUsageWindowService usageWindowService;

    // [TOPIC] 성공한 요청의 실제 사용량 정산
    @Transactional
    public void completeSuccess(Long userId, UUID requestId, BigDecimal chargedVie) {
        AiRequest request = aiRequestService.getOwnedRequest(userId, requestId);

        // [WHY] 완료된 요청의 재정산을 막고 차감량을 검증
        request.success(chargedVie);

        // [CAUTION] 주기가 변경돼도 요청 생성 시 연결한 이용 주기에 정산
        AccountUsageWindow window = usageWindowService.getOwnedWindow(userId, request.getUsageWindowId());

        // [WHY] 호출자가 전달한 예약량 대신 DB에 저장된 해당 요청의 예약량을 사용
        window.settleVie(request.getReservedVie(), request.getChargedVie());

        // [NOTE] 관리 중인 두 엔티티의 변경 사항은 트랜잭션 커밋 시 함께 반영
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

    // [TOPIC] VIE 예약과 새 AI 요청 기록 생성
    @Transactional
    public UUID reserve(
            Long userId,
            Long roomId,
            Long usageWindowId,
            String idempotencyKey,
            String requestFingerprint,
            String provider,
            String modelName,
            BigDecimal reservedVie,
            Instant processingDeadlineAt
    ) {
        ChatRoom chatRoom = chatRoomService.getOwnedRoom(userId, roomId);

        AccountUsageWindow window = usageWindowService.getOwnedWindow(userId, usageWindowId);

        AiRequest request = AiRequest.of(
                window.getUser(),
                chatRoom,
                window,
                idempotencyKey,
                requestFingerprint,
                provider,
                modelName,
                reservedVie,
                processingDeadlineAt
        );

        // [WHY] 주기를 확보한 직후 경계 시각을 지났다면 이전 주기에 새 예약을 넣지 않음
        Instant startedAt = request.getStartedAt();

        if (startedAt.isBefore(window.getPeriodStartAt()) || !startedAt.isBefore(window.getPeriodEndAt())) {
            throw new ServiceException(ErrorCode.USAGE_PERIOD_CHANGED);
        }

        // [WHY] 요청 엔티티에서 검증한 예약량과 계정에 반영하는 예약량을 일치시킴
        window.reserveVie(request.getReservedVie());

        // [NOTE] 저장이나 커밋이 실패하면 요청 기록과 예약량 변경을 함께 롤백
        AiRequest saved = aiRequestService.create(request);

        return saved.getId();
    }
}