package com.example.visualchat.global.ai.openai;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.ai_request.model.AiChatResult;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** [ROLE] OpenAI 요청 JSON과 도메인 대화 결과 사이의 변환을 담당 */
@Component
@RequiredArgsConstructor
public class OpenAiChatMapper {

    private final ObjectMapper objectMapper;

    // [TOPIC] 레브 설정과 대화 맥락을 Responses API 요청으로 변환
    public String writeRequest(AiChatRequest request) {
        List<Map<String, String>> input = inputOf(request);

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "model", request.modelName(),
                    "instructions", request.systemPrompt(),
                    "input", input,
                    "max_output_tokens", request.maxOutputTokens(),
                    "reasoning", Map.of("effort", "none"),
                    "service_tier", "default",
                    // [NOTE] 제공사의 응답 저장 기능을 사용하지 않음
                    "store", false,
                    "stream", false,
                    "truncation", "disabled",
                    // [NOTE] 명시적 중단점을 지정하지 않아 자동 캐시 쓰기를 사용하지 않음
                    "prompt_cache_options", Map.of("mode", "explicit")
            ));
        } catch (JacksonException exception) {
            // [CAUTION] 예외에 포함될 수 있는 대화 원문을 전달하지 않음
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }
    }

    // [TOPIC] 완료된 AI 답변과 실제 사용량 변환
    public AiChatResult readResult(String body, AiChatRequest request) {
        if (body == null || body.isBlank()) {
            throw invalidResponse();
        }

        try {
            JsonNode root = objectMapper.readTree(body);

            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }

            if (!"completed".equals(text(root.path("status")))
                    || !"default".equals(text(root.path("service_tier")))) {
                throw invalidResponse();
            }

            String actualModel = text(root.path("model"));
            String expectedModel = request.modelName();

            // [WHY] 다른 모델의 사용량을 예약 당시 모델 요율로 정산하지 않음
            boolean sameModel = actualModel.equals(expectedModel)
                    || actualModel.matches(Pattern.quote(expectedModel) + "-\\d{4}-\\d{2}-\\d{2}");

            if (!sameModel) {
                throw invalidResponse();
            }

            String responseId = text(root.path("id"));

            if (responseId.length() > 255) {
                throw invalidResponse();
            }

            return new AiChatResult(
                    responseId,
                    readContent(root.path("output")),
                    readUsage(root.path("usage"), request.maxOutputTokens())
            );
        } catch (JacksonException exception) {
            // [CAUTION] 제공사 응답 원문을 오류 메시지에 포함하지 않음
            throw invalidResponse();
        }
    }

    // [TOPIC] assistant 메시지의 출력 텍스트 추출
    private String readContent(JsonNode output) {
        if (!output.isArray()) {
            throw invalidResponse();
        }

        StringBuilder content = new StringBuilder();

        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asString(""))) {
                continue;
            }

            if (!"assistant".equals(item.path("role").asString(""))
                    || !"completed".equals(item.path("status").asString(""))
                    || !item.path("content").isArray()) {
                throw invalidResponse();
            }

            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asString(""))) {
                    JsonNode value = part.path("text");

                    if (!value.isString()) {
                        throw invalidResponse();
                    }

                    content.append(value.asString());
                }
            }
        }

        if (content.toString().isBlank()) {
            throw invalidResponse();
        }

        return content.toString();
    }

    // [TOPIC] 캐시 읽기·쓰기와 출력 토큰의 정산 정보 검증
    private AiTokenUsage readUsage(JsonNode usage, long maxOutputTokens) {
        long input = count(usage.path("input_tokens"));
        long output = count(usage.path("output_tokens"));

        JsonNode details = usage.path("input_tokens_details");

        long cached = count(details.path("cached_tokens"));
        long totalWrite = optionalCount(details.path("cache_write_tokens"));

        JsonNode writes = details.path("cache_write_tokens_details");

        long write30m = 0;
        long write12h = 0;

        if (!writes.isMissingNode() && !writes.isNull()) {
            write30m = count(writes.path("ttl_30m_tokens"));
            write12h = count(writes.path("ttl_12h_tokens"));
        }

        // [WHY] 정수 오버플로 없이 캐시 쓰기 합계와 상세 사용량을 비교
        if (write30m > totalWrite || write12h != totalWrite - write30m) {
            throw invalidResponse();
        }

        if (output == 0 || output > maxOutputTokens) {
            throw invalidResponse();
        }

        try {
            // [NOTE] 전체 출력 토큰에 포함된 추론 토큰을 별도로 더하지 않음
            return new AiTokenUsage(input, cached, write30m, write12h, output);

        } catch (ServiceException exception) {
            // [WHY] 제공사의 잘못된 사용량은 사용자 입력 오류와 구분
            throw invalidResponse();
        }
    }

    private long count(JsonNode value) {
        // [CAUTION] 누락·문자열·소수·범위 초과 사용량을 0으로 대체하지 않음
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidResponse();
        }

        return value.longValue();
    }

    private long optionalCount(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }

        return count(value);
    }

    private String text(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) {
            throw invalidResponse();
        }

        return value.asString();
    }

    private ServiceException invalidResponse() {
        return new ServiceException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
    }

    // [TOPIC] 계수와 생성 요청에서 동일하게 사용할 대화 입력 구성
    private List<Map<String, String>> inputOf(AiChatRequest request) {
        return request.messages().stream()
                .map(message -> {
                    String role = switch (message.sender()) {
                        case USER -> "user";
                        case ASSISTANT -> "assistant";
                    };
                    return Map.of("role", role, "content", message.content());
                })
                .toList();
    }

    // [TOPIC] 입력 토큰 계수 요청 구성
    public String writeTokenCountRequest(AiChatRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "model", request.modelName(),
                    "instructions", request.systemPrompt(),
                    "input", inputOf(request),
                    "reasoning", Map.of("effort", "none")
            ));
        } catch (JacksonException exception) {
            // [CAUTION] 레브 설정과 대화 원문이 포함된 예외를 전달하지 않음
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }
    }

    // [TOPIC] 입력 토큰 계수 결과 검증
    public long readInputTokens(String body) {
        if (body == null || body.isBlank()) {
            throw invalidResponse();
        }

        try {
            JsonNode root = objectMapper.readTree(body);

            if (root == null
                    || !root.isObject()
                    || !"response.input_tokens".equals(root.path("object").asString(""))) {
                throw invalidResponse();
            }

            // [WHY] 누락되거나 잘못된 계수 결과를 0으로 예약하지 않음
            long inputTokens = count(root.path("input_tokens"));

            if (inputTokens == 0) {
                throw invalidResponse();
            }
            return inputTokens;

        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }
}