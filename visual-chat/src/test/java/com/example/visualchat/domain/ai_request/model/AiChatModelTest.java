package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.domain.message.dto.SendMessageRequest;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.global.ai.openai.OpenAiConfig;
import com.example.visualchat.global.ai.openai.OpenAiProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** [ROLE] 입력 모델의 불변성·비공개 값 문자열 차단·요청 검증 경계를 검증 */
class AiChatModelTest {
    @Test
    @DisplayName("입력 목록을 복사해 원본 수정과 반환 목록 수정을 차단한다")
    void protectsInputSnapshot() {
        var source = new ArrayList<>(List.of(new AiChatMessage(SenderType.USER, "private-question")));
        var request = new AiChatRequest("gpt-6.1-terra", "private-prompt", source, 1024);
        source.clear();
        assertThat(request.messages()).hasSize(1);
        assertThatThrownBy(() -> request.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(request.toString()).doesNotContain("private-prompt", "private-question");
        assertThat(request.messages().getFirst().toString()).doesNotContain("private-question");
        assertThat(new AiChatResult("resp-test", "private-answer", null).toString()).doesNotContain("private-answer");
        assertThat(new SendMessageRequest("private-question").toString()).doesNotContain("private-question");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @DisplayName("요청 DTO는 누락·공백 입력을 거절한다")
    void validatesBlankRequest(String content) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new SendMessageRequest(content))).isNotEmpty();
        }
    }

    @Test
    @DisplayName("요청 DTO는 2000자 경계를 내부 검증과 동일하게 적용한다")
    void validatesRequestLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new SendMessageRequest("가".repeat(2000)))).isEmpty();
            assertThat(validator.validate(new SendMessageRequest("가".repeat(2001)))).isNotEmpty();
        }
    }

    @Test
    @DisplayName("OpenAI 설정 문자열에는 키를 출력하지 않고 리다이렉트를 차단한다")
    void protectsConfiguration() {
        var settings = new OpenAiProperties("unit-test-token", Duration.ofSeconds(5), Duration.ofSeconds(30));
        assertThat(settings.toString()).doesNotContain("unit-test-token").contains("REDACTED");
        try (var http = new OpenAiConfig().openAiHttpClient(settings)) {
            assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(http.connectTimeout()).contains(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1000, 0, 999})
    @DisplayName("연결과 요청 제한 시간은 모두 1초 이상이어야 한다")
    void rejectsInvalidTimeout(long millis) {
        assertThatIllegalArgumentException().isThrownBy(() -> new OpenAiProperties("", Duration.ofMillis(millis), Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() -> new OpenAiProperties("", Duration.ofSeconds(5), Duration.ofMillis(millis)));
    }
}
