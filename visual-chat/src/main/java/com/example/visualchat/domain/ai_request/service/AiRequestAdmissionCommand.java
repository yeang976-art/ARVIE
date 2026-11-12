package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowCommand;
import com.example.visualchat.domain.ai_request.model.AiRequestReceipt;
import com.example.visualchat.domain.ai_request.model.AiRequestSnapshot;
import com.example.visualchat.domain.ai_request.model.IdempotencyKey;
import com.example.visualchat.domain.ai_request.util.AiRequestFingerprintGenerator;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * [ROLE] 멱등 접수 판정과 신규 예약·동시 접수 충돌 복구를 조율
 */
@Service
@RequiredArgsConstructor
public class AiRequestAdmissionCommand {

    private final AiRequestService aiRequestService;
    private final AiRequestCommand aiRequestCommand;
    private final AccountUsageWindowCommand usageWindowCommand;
    private final AiRequestFingerprintGenerator fingerprintGenerator;

    // [WHY] 예약 트랜잭션이 완전히 롤백된 뒤 별도 트랜잭션으로 기존 요청을 조회
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiRequestReceipt admit(
            Long userId,
            Long roomId,
            String rawKey,
            String content,
            long inputTokenUpperBound,
            long maxOutputTokens,
            Instant processingDeadlineAt
    ) {
        IdempotencyKey key = new IdempotencyKey(rawKey);
        String fingerprint = fingerprintGenerator.generate(roomId, content);

        Optional<AiRequestSnapshot> existing = aiRequestService.findByKey(userId, key);

        // [WHY] 기존 요청의 재전송은 현재 잔여량과 관계없이 기존 결과로 연결
        if (existing.isPresent()) {
            return AiRequestReceipt.existing(existing.get(), fingerprint);
        }

        UsageResponse window = usageWindowCommand.getCurrentUsage(userId);

        try {
            UUID requestId = aiRequestCommand.reserve(
                    userId,
                    roomId,
                    window.usageWindowId(),
                    key.value(),
                    fingerprint,
                    inputTokenUpperBound,
                    maxOutputTokens,
                    processingDeadlineAt
            );

            // [NOTE] 별도 Bean의 예약 트랜잭션이 커밋된 뒤 신규 접수 결과를 반환
            return AiRequestReceipt.created(requestId);

        } catch (DataIntegrityViolationException exception) {
            // [CAUTION] 멱등키 충돌 이외의 DB 제약 오류는 복구 대상으로 취급하지 않음
            if (!isDuplicateKey(exception)) {
                throw exception;
            }

            AiRequestSnapshot saved = aiRequestService
                    .findByKey(userId, key)
                    .orElseThrow(() -> new ServiceException(ErrorCode.SERVER_ERROR));

            return AiRequestReceipt.existing(saved, fingerprint);

        } catch (OptimisticLockingFailureException | ServiceException exception) {
            // [WHY] 동시 요청이 먼저 예약을 커밋하면 잔액 부족이나 버전 충돌로 실패할 수도 있음
            Optional<AiRequestSnapshot> saved = aiRequestService.findByKey(userId, key);

            if (saved.isPresent()) {
                return AiRequestReceipt.existing(saved.get(), fingerprint);
            }

            // [NOTE] 같은 키의 접수 기록이 없다면 원래 실패 원인을 그대로 전달
            throw exception;
        }
    }

    // [TOPIC] 회원별 멱등키 유일성 제약 위반 판별
    private boolean isDuplicateKey(Throwable error) {
        Throwable cause = error;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uk_ai_requests_user_idempotency_key".equals(violation.getConstraintName())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}