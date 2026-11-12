package com.example.visualchat.global.ai.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** [ROLE] OpenAI 인증 정보와 외부 호출 제한 시간을 관리 */
@ConfigurationProperties(prefix = "ai.openai")
public record OpenAiProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout
) {

    public OpenAiProperties {
        if (connectTimeout == null || connectTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("OpenAI 연결 제한 시간은 1초 이상이어야 합니다.");
        }

        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("OpenAI 요청 제한 시간은 1초 이상이어야 합니다.");
        }
    }

    // [CAUTION] 설정 객체를 로그에 출력해도 API 키가 노출되지 않도록 처리
    @Override
    public String toString() {
        return "OpenAiProperties[apiKey=[REDACTED]"
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }
}