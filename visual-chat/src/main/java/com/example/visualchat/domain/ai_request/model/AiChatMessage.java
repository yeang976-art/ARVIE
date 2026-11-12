package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.domain.message.enums.SenderType;

/** [ROLE] AI에 전달할 대화의 발신자와 본문을 보관 */
public record AiChatMessage(SenderType sender, String content) {

    // [CAUTION] 대화 원문이 기본 문자열 표현을 통해 로그에 노출되는 것을 방지
    @Override
    public String toString() {
        return "AiChatMessage[sender=" + sender + "]";
    }
}