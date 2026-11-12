package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;

import java.util.UUID;

/** [ROLE] 멱등 접수 판정에 필요한 기존 요청 정보를 전달 */
public record AiRequestSnapshot(UUID requestId, AiRequestStatus status, String requestFingerprint) {
    public static AiRequestSnapshot from(AiRequest request) {
        return new AiRequestSnapshot(request.getId(), request.getStatus(), request.getRequestFingerprint());
    }

    // [TOPIC] 동일 키로 전달된 요청 내용의 일치 여부 검증
    public void verifyFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.equals(requestFingerprint)) {
            throw new ServiceException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }
}