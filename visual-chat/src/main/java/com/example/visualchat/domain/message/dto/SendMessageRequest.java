package com.example.visualchat.domain.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** [ROLE] 사용자 메시지를 전달하고 필수값·길이를 검증 */
public record SendMessageRequest(
        @NotBlank
        @Size(max = 2_000)
        String content
) {
    // [CAUTION] 요청 객체를 로그에 출력해도 대화 원문이 노출되지 않도록 처리
    @Override
    public String toString() {
        return "SendMessageRequest[content=[REDACTED]]";
    }
}