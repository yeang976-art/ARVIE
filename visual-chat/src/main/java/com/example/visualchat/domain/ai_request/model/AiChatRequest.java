package com.example.visualchat.domain.ai_request.model;

import java.util.List;

/** [ROLE] 모델·레브 설정·유효한 대화 맥락·출력 상한을 전달 */
public record AiChatRequest(
        String modelName,
        String systemPrompt,
        List<AiChatMessage> messages,
        long maxOutputTokens
) {

    public AiChatRequest {
        // [WHY] 요청 준비 후 원본 목록이 변경돼도 전달할 대화 맥락을 유지
        messages = List.copyOf(messages);
    }

    // [CAUTION] 레브 내부 설정과 대화 원문을 문자열 표현에 포함하지 않음
    @Override
    public String toString() {
        return "AiChatRequest[modelName=" + modelName
                + ", messageCount=" + messages.size()
                + ", maxOutputTokens=" + maxOutputTokens + "]";
    }
}