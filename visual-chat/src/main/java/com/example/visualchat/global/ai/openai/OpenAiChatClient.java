package com.example.visualchat.global.ai.openai;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.ai_request.model.AiChatResult;
import com.example.visualchat.domain.ai_request.port.AiChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** [ROLE] OpenAI Responses API 호출과 통신 오류 변환을 담당 */
@Component
public class OpenAiChatClient implements AiChatClient {

    // [CAUTION] 인증 정보를 전송할 제공사 주소를 고정
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final URI TOKEN_COUNT_ENDPOINT = URI.create("https://api.openai.com/v1/responses/input_tokens");

    private final HttpClient httpClient;
    private final OpenAiProperties properties;
    private final OpenAiChatMapper mapper;

    // [WHY] OpenAI용 HTTP 클라이언트를 명시적으로 선택하기 위해 생성자에 Qualifier 적용
    public OpenAiChatClient(
            @Qualifier("openAiHttpClient") HttpClient httpClient,
            OpenAiProperties properties,
            OpenAiChatMapper mapper
    ) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public AiChatResult generate(AiChatRequest request) {
        HttpResponse<String> response = post(ENDPOINT, mapper.writeRequest(request));

        return mapper.readResult(response.body(), request);
    }

    @Override
    public long countInputTokens(AiChatRequest request) {
        HttpResponse<String> response = post(TOKEN_COUNT_ENDPOINT, mapper.writeTokenCountRequest(request));

        return mapper.readInputTokens(response.body());
    }

    // [TOPIC] 인증·트랜잭션 경계를 검증한 뒤 제공사에 요청
    private HttpResponse<String> post(URI endpoint, String body) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new ServiceException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }

        // [WHY] 토큰 계수를 포함한 외부 통신 중 DB 트랜잭션을 유지하지 않음
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        HttpResponse<String> response = execute(buildRequest(endpoint, body));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // [CAUTION] 제공사 오류 본문을 그대로 전달하지 않음
            throw new ServiceException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }

        return response;
    }

    private HttpRequest buildRequest(URI endpoint, String body) {
        try {
            return HttpRequest.newBuilder(endpoint)
                    .timeout(properties.requestTimeout())
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

        } catch (IllegalArgumentException exception) {
            // [CAUTION] 인증 헤더가 포함될 수 있는 원본 예외를 전달하지 않음
            throw new ServiceException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    // [TOPIC] 응답 본문 수신까지 제한 시간을 적용
    private HttpResponse<String> execute(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        try {
            return pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new ServiceException(ErrorCode.AI_PROVIDER_TIMEOUT);

        } catch (InterruptedException exception) {
            pending.cancel(true);

            // [WHY] 작업 중단 신호를 상위 실행 흐름에서도 확인할 수 있도록 복원
            Thread.currentThread().interrupt();

            throw new ServiceException(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new ServiceException(ErrorCode.AI_PROVIDER_TIMEOUT);
            }

            throw new ServiceException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }
}