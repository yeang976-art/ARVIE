package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;

import java.util.UUID;

/** [ROLE] 접수된 요청과 신규 접수 여부를 내부 호출자에게 전달 */
public record AiRequestReceipt(UUID requestId, AiRequestStatus status, boolean newlyCreated) {
    public static AiRequestReceipt created(UUID requestId) {
        return new AiRequestReceipt(requestId, AiRequestStatus.PROCESSING, true);
    }

    public static AiRequestReceipt existing(AiRequestSnapshot snapshot, String fingerprint) {
        snapshot.verifyFingerprint(fingerprint);

        return new AiRequestReceipt(snapshot.requestId(), snapshot.status(), false);
    }
}