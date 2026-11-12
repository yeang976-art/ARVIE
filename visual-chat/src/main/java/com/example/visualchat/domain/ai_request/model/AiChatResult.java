package com.example.visualchat.domain.ai_request.model;

/** [ROLE] AI 답변·제공사 요청 식별자·실제 토큰 사용량을 전달 */
public record AiChatResult(String providerRequestId, String content, AiTokenUsage usage) {

    // [CAUTION] AI 답변 원문을 문자열 표현에 포함하지 않음
    @Override
    public String toString() {
        return "AiChatResult[providerRequestId=" + providerRequestId + "]";
    }
}