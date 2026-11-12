package com.example.visualchat.global.ai.openai;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiChatMessage;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.ai_request.model.AiChatResult;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.message.enums.SenderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** [ROLE] 실제 네트워크 없이 인증·호출 경계·제한 시간·통신 오류 변환을 검증 */
class OpenAiChatClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final OpenAiChatMapper mapper = mock(OpenAiChatMapper.class);
    // [CAUTION] 실제 키 형식이 아닌 테스트 전용 헤더 값
    private final OpenAiProperties properties = new OpenAiProperties("unit-test-token", Duration.ofSeconds(5), Duration.ofSeconds(30));
    private final OpenAiChatClient client = new OpenAiChatClient(http, properties, mapper);
    private final AiChatRequest input = new AiChatRequest("gpt-6.1-terra", "내부 설정",
            List.of(new AiChatMessage(SenderType.USER, "질문")), 1024);

    @AfterEach
    void clearsThreadState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Thread.interrupted();
    }

    @Test
    @DisplayName("생성 요청은 고정 주소·Bearer 인증·제한 시간을 적용하고 결과를 변환한다")
    void sendsGenerationRequest() {
        when(mapper.writeRequest(input)).thenReturn("{}");
        HttpResponse<String> response = response(200, "provider-result");
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        var expected = new AiChatResult("resp-test", "답변", new AiTokenUsage(100, 0, 0, 0, 20));
        when(mapper.readResult("provider-result", input)).thenReturn(expected);
        assertThat(client.generate(input)).isSameAs(expected);
        var capture = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(capture.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        HttpRequest request = capture.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(Duration.ofSeconds(30));
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer unit-test-token");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("입력 계수는 전용 주소만 호출하고 답변 생성 변환을 수행하지 않는다")
    void countsWithoutGenerating() {
        when(mapper.writeTokenCountRequest(input)).thenReturn("{}");
        HttpResponse<String> counted = response(200, "count-result");
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(counted));
        when(mapper.readInputTokens("count-result")).thenReturn(1000L);
        assertThat(client.countInputTokens(input)).isEqualTo(1000);
        verify(http).sendAsync(argThat(request -> request.uri().toString()
                .equals("https://api.openai.com/v1/responses/input_tokens")),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        verify(mapper, never()).readResult(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("키가 없으면 실제 전송을 하지 않는다")
    void preventsNetworkWithoutKey(String key) {
        when(mapper.writeRequest(input)).thenReturn("{}");
        var missingKey = new OpenAiChatClient(http,
                new OpenAiProperties(key, Duration.ofSeconds(5), Duration.ofSeconds(30)), mapper);
        error(ErrorCode.AI_PROVIDER_UNAVAILABLE, () -> missingKey.generate(input));
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("DB 트랜잭션이 열려 있으면 생성과 계수 모두 전송 전에 차단한다")
    void preventsNetworkInsideTransaction() {
        when(mapper.writeRequest(input)).thenReturn("{}");
        when(mapper.writeTokenCountRequest(input)).thenReturn("{}");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        error(ErrorCode.SERVER_ERROR, () -> client.generate(input));
        error(ErrorCode.SERVER_ERROR, () -> client.countInputTokens(input));
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 429, 500, 503})
    @DisplayName("실패 HTTP 상태는 재호출하거나 원문을 반환하지 않는다")
    void handlesProviderHttpError(int status) {
        when(mapper.writeRequest(input)).thenReturn("{}");
        HttpResponse<String> failed = response(status, "비공개-제공사-오류");
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.completedFuture(failed));
        error(ErrorCode.AI_PROVIDER_UNAVAILABLE, () -> client.generate(input));
        verify(http, times(1)).sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        verify(mapper, never()).readResult(any(), any());
    }

    @Test
    @DisplayName("본문 수신까지 시간 초과 시 취소를 요청하고 재호출하지 않는다")
    void cancelsOnDeadline() throws Exception {
        when(mapper.writeRequest(input)).thenReturn("{}");
        @SuppressWarnings("unchecked") CompletableFuture<HttpResponse<String>> pending = mock(CompletableFuture.class);
        when(pending.get(30_000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException());
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        error(ErrorCode.AI_PROVIDER_TIMEOUT, () -> client.generate(input));
        verify(pending).cancel(true);
        verify(http, times(1)).sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    @DisplayName("중단 신호를 복원하고 진행 중인 통신을 취소한다")
    void preservesInterruption() throws Exception {
        when(mapper.writeRequest(input)).thenReturn("{}");
        @SuppressWarnings("unchecked") CompletableFuture<HttpResponse<String>> pending = mock(CompletableFuture.class);
        when(pending.get(30_000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        error(ErrorCode.AI_PROVIDER_UNAVAILABLE, () -> client.generate(input));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(pending).cancel(true);
    }

    @Test
    @DisplayName("HTTP 제한 시간과 일반 연결 실패를 구분한다")
    void mapsAsyncFailures() {
        when(mapper.writeRequest(input)).thenReturn("{}");
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new HttpTimeoutException("timeout")));
        error(ErrorCode.AI_PROVIDER_TIMEOUT, () -> client.generate(input));
        when(http.sendAsync(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("private-error")));
        error(ErrorCode.AI_PROVIDER_UNAVAILABLE, () -> client.generate(input));
    }

    @Test
    @DisplayName("헤더에 사용할 수 없는 키는 원본 예외 노출 없이 거절한다")
    void rejectsInvalidHeader() {
        when(mapper.writeRequest(input)).thenReturn("{}");
        var invalidKey = new OpenAiChatClient(http,
                new OpenAiProperties("invalid\r\nheader", Duration.ofSeconds(5), Duration.ofSeconds(30)), mapper);
        error(ErrorCode.AI_PROVIDER_UNAVAILABLE, () -> invalidKey.generate(input));
        verifyNoInteractions(http);
    }

    private HttpResponse<String> response(int code, String body) {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        return response;
    }

    private void error(ErrorCode code, Runnable action) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(action::run)
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain("private-error", "비공개-제공사-오류", "invalid\r\nheader");
                });
    }
}
