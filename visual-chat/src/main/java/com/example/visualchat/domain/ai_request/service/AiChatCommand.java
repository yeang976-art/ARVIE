package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.dto.AiRequestStatusResponse;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.ai_request.model.AiChatResult;
import com.example.visualchat.domain.ai_request.model.AiRequestReceipt;
import com.example.visualchat.domain.ai_request.model.AiRequestSnapshot;
import com.example.visualchat.domain.ai_request.model.IdempotencyKey;
import com.example.visualchat.domain.ai_request.port.AiChatClient;
import com.example.visualchat.domain.ai_request.util.AiRequestFingerprintGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** [ROLE] 메시지 접수·AI 호출·저장·VIE 정산의 전체 흐름을 조율 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatCommand {

    // [NOTE] 현재 MVP에서 허용할 전체 입력 토큰 상한
    private static final long MAX_INPUT_TOKENS = 16_384L;

    // [NOTE] 현재 기본 외부 호출 제한 시간 30초에 저장 여유를 더한 처리 기한
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(2);

    private final Clock clock;
    private final AiChatClient aiChatClient;
    private final AiRequestService aiRequestService;
    private final AiRequestCommand aiRequestCommand;
    private final AiRequestStatusCommand statusCommand;
    private final AiRequestAdmissionCommand admissionCommand;
    private final AiChatPreparationCommand preparationCommand;
    private final AiChatPersistenceCommand persistenceCommand;
    private final AiRequestFingerprintGenerator fingerprintGenerator;

    // [WHY] 외부 호출을 감싸는 트랜잭션을 만들지 않고 각 저장 Command에서 짧게 처리
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiRequestStatusResponse send(Long userId, Long roomId, String rawKey, String content) {
        IdempotencyKey key = new IdempotencyKey(rawKey);
        String fingerprint = fingerprintGenerator.generate(roomId, content);

        Optional<AiRequestSnapshot> existing = aiRequestService.findByKey(userId, key);

        // [WHY] 기존 요청은 현재 맥락·한도·제공사 연결 상태와 관계없이 재조회
        if (existing.isPresent()) {
            existing.get().verifyFingerprint(fingerprint);

            return statusCommand.getStatus(userId, existing.get().requestId());
        }

        // [NOTE] 이 단계에서 채팅방 소유권과 사용자 입력을 검증
        AiChatRequest input = preparationCommand.prepare(userId, roomId, content);

        long inputTokens = aiChatClient.countInputTokens(input);

        if (inputTokens <= 0) {
            throw new ServiceException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }

        if (inputTokens > MAX_INPUT_TOKENS) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "대화 맥락이 현재 허용하는 입력량을 초과했습니다.");
        }

        Instant deadline = clock.instant().plus(PROCESSING_TTL);

        AiRequestReceipt receipt = admissionCommand.admit(
                userId,
                roomId,
                key.value(),
                content,
                inputTokens,
                input.maxOutputTokens(),
                deadline
        );

        // [WHY] 계수 중 다른 요청이 같은 키를 먼저 접수했다면 생성하지 않음
        if (!receipt.newlyCreated()) {
            return statusCommand.getStatus(userId, receipt.requestId());
        }

        UUID requestId = receipt.requestId();
        AiChatResult result;

        try {
            persistenceCommand.saveUserMessage(userId, requestId, content);

            // [CAUTION] 원자적 VIE 예약이 커밋된 신규 요청만 답변 생성
            result = aiChatClient.generate(input);

            validateUsage(result, inputTokens, input.maxOutputTokens());

        } catch (ServiceException exception) {
            completeFailure(userId, requestId, exception.getErrorCode());
            throw exception;

        } catch (RuntimeException exception) {
            completeFailure(userId, requestId, ErrorCode.SERVER_ERROR);
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        try {
            // [NOTE] 답변·제공사 응답 ID·요청 완료·정산을 함께 반영
            persistenceCommand.completeSuccess(userId, requestId, result);

        } catch (RuntimeException exception) {
            // [CAUTION] 커밋 결과가 불명확할 수 있으므로 실패로 덮어쓰거나 AI를 재호출하지 않음
            log.error(
                    "AI 요청 완료 저장 확인 필요: requestId={}, errorType={}",
                    requestId,
                    exception.getClass().getSimpleName()
            );

            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        return statusCommand.getStatus(userId, requestId);
    }

    // [TOPIC] 예약에 사용한 입력량과 출력 상한을 실제 사용량이 넘는지 검증
    private void validateUsage(AiChatResult result, long inputTokens, long maxOutputTokens) {
        if (result == null
                || result.usage() == null
                || result.usage().inputTokens() > inputTokens
                || result.usage().outputTokens() > maxOutputTokens) {
            throw new ServiceException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
    }

    // [TOPIC] 실패 상태와 예약 해제를 함께 반영
    private void completeFailure(Long userId, UUID requestId, ErrorCode errorCode) {
        try {
            aiRequestCommand.completeFailure(userId, requestId, errorCode);

        } catch (RuntimeException exception) {
            // [CAUTION] 원문·인증 정보·외부 예외 메시지를 로그에 남기지 않음
            log.error(
                    "AI 요청 실패 정산 확인 필요: requestId={}, errorType={}",
                    requestId,
                    exception.getClass().getSimpleName()
            );

            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }
    }
}