package com.example.visualchat.global.ai.openai;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiChatMessage;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.message.enums.SenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 제공사 프로토콜 변환과 잘못된 답변·정산 사용량의 차단을 검증 */
class OpenAiChatMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OpenAiChatMapper mapper = new OpenAiChatMapper(json);
    private final AiChatRequest request = new AiChatRequest("gpt-6.1-terra", "비공개 레브 설정",
            List.of(new AiChatMessage(SenderType.USER, "이전 질문"),
                    new AiChatMessage(SenderType.ASSISTANT, "이전 답변"),
                    new AiChatMessage(SenderType.USER, "새 질문🌸\n공백 유지 ")), 1024);

    @Test
    @DisplayName("생성과 계수는 모델·설정·역할·원문·추론 설정이 같은 입력을 사용한다")
    void usesSameInputForCountingAndGeneration() {
        JsonNode generation = json.readTree(mapper.writeRequest(request));
        JsonNode counting = json.readTree(mapper.writeTokenCountRequest(request));
        for (String name : List.of("model", "instructions", "input", "reasoning")) {
            assertThat(counting.path(name)).isEqualTo(generation.path(name));
        }
        assertThat(generation.path("input").get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(generation.path("input").get(2).path("content").asString()).isEqualTo("새 질문🌸\n공백 유지 ");
        assertThat(generation.path("store").asBoolean()).isFalse();
        assertThat(generation.path("stream").asBoolean()).isFalse();
        assertThat(generation.path("truncation").asString()).isEqualTo("disabled");
        assertThat(generation.path("service_tier").asString()).isEqualTo("default");
        assertThat(generation.path("prompt_cache_options").path("mode").asString()).isEqualTo("explicit");
        assertThat(generation.path("max_output_tokens").asLong()).isEqualTo(1024);
        assertThat(counting.has("max_output_tokens")).isFalse();
    }

    @Test
    @DisplayName("텍스트 조각을 합치고 캐시 유형별 사용량과 전체 출력 토큰을 보존한다")
    void mapsTextAndUsageWithoutDoubleCountingReasoning() {
        var result = mapper.readResult(body().toString(), request);
        assertThat(result.providerRequestId()).isEqualTo("resp-test");
        assertThat(result.content()).isEqualTo("안녕🌸\n반가워!");
        assertThat(result.usage().inputTokens()).isEqualTo(1000);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(100);
        assertThat(result.usage().cacheWrite30mTokens()).isEqualTo(200);
        assertThat(result.usage().cacheWrite12hTokens()).isEqualTo(100);
        assertThat(result.usage().ordinaryInputTokens()).isEqualTo(600);
        assertThat(result.usage().outputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("동일 모델의 날짜 버전을 허용한다")
    void acceptsDatedSnapshot() {
        ObjectNode body = body();
        body.put("model", "gpt-6.1-terra-2026-10-26");
        assertThat(mapper.readResult(body.toString(), request).content()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "failed", "in_progress", "queued"})
    @DisplayName("미완료·실패 상태의 응답은 성공 답변으로 변환하지 않는다")
    void rejectsNonCompletedResponse(String status) {
        ObjectNode body = body();
        body.put("status", status);
        invalid(() -> mapper.readResult(body.toString(), request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"priority", "flex", "auto"})
    @DisplayName("표준 요율과 다른 처리 등급을 현재 요율로 정산하지 않는다")
    void rejectsOtherTier(String tier) {
        ObjectNode body = body();
        body.put("service_tier", tier);
        invalid(() -> mapper.readResult(body.toString(), request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-6.1-sol", "gpt-6.1-terra-other", "gpt-6.1-terra-2026-1-1"})
    @DisplayName("다른 모델이나 예상하지 못한 모델 식별자를 거절한다")
    void rejectsOtherModel(String model) {
        ObjectNode body = body();
        body.put("model", model);
        invalid(() -> mapper.readResult(body.toString(), request));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{broken", "null", "[]", "42", "{}"})
    @DisplayName("빈 값·잘못된 JSON·잘못된 최상위 구조를 제공사 응답 오류로 처리한다")
    void rejectsInvalidBody(String text) {
        invalid(() -> mapper.readResult(text, request));
        invalid(() -> mapper.readInputTokens(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "status", "model", "service_tier", "output", "usage"})
    @DisplayName("필수 응답 정보 누락을 성공으로 처리하지 않는다")
    void rejectsMissingResponseField(String field) {
        ObjectNode body = body();
        body.remove(field);
        invalid(() -> mapper.readResult(body.toString(), request));
    }

    @Test
    @DisplayName("저장 범위를 넘는 응답 식별자와 텍스트 없는 답변을 거절한다")
    void rejectsOversizedIdAndMissingText() {
        ObjectNode oversized = body();
        oversized.put("id", "r".repeat(256));
        invalid(() -> mapper.readResult(oversized.toString(), request));
        ObjectNode body = body();
        body.putArray("output");
        String empty = body.toString();
        invalid(() -> mapper.readResult(empty, request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "\"100\"", "null", "9223372036854775808"})
    @DisplayName("누락·음수·소수·문자열·범위 초과 사용량을 0으로 대체하지 않는다")
    void rejectsInvalidNumericUsage(String number) {
        ObjectNode body = body();
        ((ObjectNode) body.path("usage")).set("input_tokens", json.readTree(number));
        invalid(() -> mapper.readResult(body.toString(), request));
        invalid(() -> mapper.readInputTokens("{\"object\":\"response.input_tokens\",\"input_tokens\":" + number + "}"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1025})
    @DisplayName("빈 출력이나 요청한 출력 상한을 넘는 사용량을 거절한다")
    void rejectsInvalidOutputCount(long count) {
        ObjectNode body = body();
        ((ObjectNode) body.path("usage")).put("output_tokens", count);
        invalid(() -> mapper.readResult(body.toString(), request));
    }

    @Test
    @DisplayName("캐시 쓰기 합계 불일치와 전체 입력을 넘는 캐시 사용량을 거절한다")
    void rejectsInvalidCachePartitions() {
        ObjectNode body = body();
        ((ObjectNode) body.path("usage").path("input_tokens_details")).put("cache_write_tokens", 301);
        invalid(() -> mapper.readResult(body.toString(), request));
        ObjectNode excessive = body();
        ((ObjectNode) excessive.path("usage")).put("input_tokens", 399);
        invalid(() -> mapper.readResult(excessive.toString(), request));
    }

    @Test
    @DisplayName("캐시 쓰기 상세가 없으면 쓰기 합계도 0일 때만 허용한다")
    void handlesMissingOptionalCacheWriteDetails() {
        ObjectNode body = body();
        ObjectNode details = (ObjectNode) body.path("usage").path("input_tokens_details");
        details.remove("cache_write_tokens_details");
        invalid(() -> mapper.readResult(body.toString(), request));
        details.remove("cache_write_tokens");
        assertThat(mapper.readResult(body.toString(), request).usage().cacheWrite30mTokens()).isZero();
    }

    @Test
    @DisplayName("계수 응답의 종류와 양수 입력량을 검증한다")
    void validatesCountResponse() {
        assertThat(mapper.readInputTokens("{\"object\":\"response.input_tokens\",\"input_tokens\":1000}")).isEqualTo(1000);
        invalid(() -> mapper.readInputTokens("{\"object\":\"response\",\"input_tokens\":1000}"));
        invalid(() -> mapper.readInputTokens("{\"object\":\"response.input_tokens\",\"input_tokens\":0}"));
    }

    private ObjectNode body() {
        return (ObjectNode) json.readTree("""
                {"id":"resp-test","status":"completed","model":"gpt-6.1-terra","service_tier":"default",
                 "output":[{"type":"reasoning"},{"type":"message","role":"assistant","status":"completed",
                    "content":[{"type":"output_text","text":"안녕🌸\\n"},{"type":"output_text","text":"반가워!"}]}],
                 "usage":{"input_tokens":1000,"output_tokens":200,"output_tokens_details":{"reasoning_tokens":50},
                    "input_tokens_details":{"cached_tokens":100,"cache_write_tokens":300,
                      "cache_write_tokens_details":{"ttl_30m_tokens":200,"ttl_12h_tokens":100}}}}
                """);
    }

    private void invalid(Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_INVALID_RESPONSE));
    }
}
