package com.example.visualchat.domain.message.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.*;
import com.example.visualchat.domain.ai_request.port.AiChatClient;
import com.example.visualchat.domain.ai_request.repository.AiRequestRepository;
import com.example.visualchat.domain.ai_request.service.AiRequestCommand;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.repository.ChatRoomRepository;
import com.example.visualchat.domain.message.entity.Message;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.repository.MessageRepository;
import com.example.visualchat.domain.message.service.MessageService;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** [ROLE] H2와 PostgreSQL에서 같은 사용자 흐름·정산·원문 보관 계약을 실제 JWT로 검증 */
abstract class AiChatIntegrationContract {
    protected static final String CONTENT = "비공개 신규 질문🌸";
    protected static final String ANSWER = "비공개 레브 답변🌷";
    protected static final String PROMPT = "외부에 노출하지 않을 레브 내부 설정";
    protected static final AiTokenUsage USAGE = new AiTokenUsage(1000, 0, 0, 0, 200);
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JwtEncoder encoder;
    @Autowired protected AiRequestRepository requests;
    @Autowired protected MessageRepository messages;
    @Autowired protected AccountUsageWindowRepository windows;
    @Autowired protected UserRepository users;
    @Autowired protected ReveRepository reves;
    @Autowired protected UserReveRepository assignments;
    @Autowired protected ChatRoomRepository rooms;
    @Autowired protected AiRequestCommand requestCommand;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected ChatTestClock clock;
    // [CAUTION] 실제 제공자 Bean을 대체해 네트워크 요청과 API 비용을 차단
    @MockitoBean protected AiChatClient provider;
    @MockitoSpyBean protected MessageService messageService;
    protected Owner owner;
    protected Owner other;
    protected Instant readAt;
    protected final ConcurrentLinkedQueue<AiChatRequest> counted = new ConcurrentLinkedQueue<>();
    protected final ConcurrentLinkedQueue<AiChatRequest> generated = new ConcurrentLinkedQueue<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary ChatTestClock chatTestClock() { return new ChatTestClock(); }
    }

    static final class ChatTestClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>(Instant.now());
        void set(Instant time) { value.set(time); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return value.get(); }
    }

    @DynamicPropertySource
    static void authentication(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void isolatedDatabaseAndFakeProvider() {
        reset(provider, messageService);
        counted.clear();
        generated.clear();
        for (String table : List.of("messages", "ai_requests", "chat_rooms", "user_reves", "account_usage_windows", "users", "reves")) {
            jdbc.update("delete from " + table);
        }
        readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(readAt);
        owner = createOwner("owner");
        other = createOwner("other");
        when(provider.countInputTokens(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            counted.add(invocation.getArgument(0));
            return 1000L;
        });
        when(provider.generate(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            generated.add(invocation.getArgument(0));
            // [WHY] 실제 생성 시점에 신규 요청과 사용자 메시지가 이미 커밋됐는지 확인
            assertThat(jdbc.queryForObject("select count(*) from messages where sender='USER'", Long.class)).isPositive();
            return new AiChatResult("resp-" + UUID.randomUUID(), ANSWER, USAGE);
        });
    }

    @Test
    @DisplayName("실제 JWT 전송은 사용자·AI 메시지와 제공사 ID·토큰·20 VIE를 함께 저장한다")
    void sendsAndSettlesThenContinuesSameRoom() throws Exception {
        MockHttpServletResponse first = send(owner, "first-key", CONTENT);
        Map<?, ?> firstData = data(first, 200);
        assertThat(firstData.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Map<?, ?>) firstData.get("answer")).get("content")).isEqualTo(ANSWER);
        assertThat(text(first)).doesNotContain(PROMPT, "providerRequestId", "passwordHash", "systemPrompt");
        AiRequest stored = requests.findAll().getFirst();
        assertThat(stored.getProviderRequestId()).startsWith("resp-");
        assertThat(stored.getInputTokens()).isEqualTo(1000L);
        assertThat(stored.getOutputTokens()).isEqualTo(200L);
        assertThat(stored.getChargedVie()).isEqualByComparingTo("20");
        assertBalances("20", "0");
        assertThat(messages.count()).isEqualTo(2);
        for (Message message : messages.findAll()) {
            assertThat(Duration.between(message.getSentAt(), message.getExpiresAt())).isEqualTo(Duration.ofDays(90));
        }
        assertThat(data(query(owner, stored.getId()), 200).get("requestId")).isEqualTo(stored.getId().toString());
        data(send(owner, "second-key", "다음 질문"), 200);
        assertThat(rooms.count()).isEqualTo(2); // [NOTE] 본인과 다른 회원의 방 각각 1개
        assertThat(generated).hasSize(2);
        assertThat(generated.stream().toList().get(1).messages()).extracting(AiChatMessage::content)
                .containsExactly(CONTENT, ANSWER, "다음 질문");
        assertThat(counted.stream().toList()).containsExactlyElementsOf(generated);
        assertBalances("40", "0");
    }

    @Test
    @DisplayName("성공한 키 재전송은 같은 요청을 반환하고 한도·메시지·제공사 호출을 늘리지 않는다")
    void replaysSuccess() throws Exception {
        Map<?, ?> first = data(send(owner, "same-key", CONTENT), 200);
        Map<?, ?> replay = data(send(owner, "same-key", CONTENT), 200);
        assertThat(replay.get("requestId")).isEqualTo(first.get("requestId"));
        verify(provider, times(1)).countInputTokens(any());
        verify(provider, times(1)).generate(any());
        assertThat(requests.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(2);
        assertBalances("20", "0");
    }

    @Test
    @DisplayName("같은 키에 다른 본문은 충돌이며 외부 호출과 차감을 반복하지 않는다")
    void rejectsDifferentContent() throws Exception {
        data(send(owner, "same-key", CONTENT), 200);
        error(send(owner, "same-key", "다른 원문"), 409, ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        verify(provider, times(1)).generate(any());
        verify(provider, times(1)).countInputTokens(any());
        assertBalances("20", "0");
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE", "AI_PROVIDER_INVALID_RESPONSE"})
    @DisplayName("생성 실패는 사용자 메시지를 남기고 예약 해제·0 차감 후 재전송에도 재생성하지 않는다")
    void failureIsFreeAndReplayIsStable(ErrorCode failure) throws Exception {
        doThrow(new ServiceException(failure)).when(provider).generate(any());
        error(send(owner, "failed-key", CONTENT), failure.getStatus().value(), failure);
        AiRequest request = requests.findAll().getFirst();
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(request.getErrorCode()).isEqualTo(failure.getCode());
        assertThat(request.getChargedVie()).isEqualByComparingTo("0");
        assertThat(messages.findAll()).extracting(Message::getSender).containsExactly(SenderType.USER);
        assertBalances("0", "0");
        assertThat(data(send(owner, "failed-key", CONTENT), 200).get("status")).isEqualTo("FAILED");
        verify(provider, times(1)).generate(any());
        verify(provider, times(1)).countInputTokens(any());
    }

    @Test
    @DisplayName("한도 부족은 요청·메시지를 만들지 않고 답변 생성을 호출하지 않는다")
    void doesNotGenerateWhenBudgetIsInsufficient() throws Exception {
        jdbc.update("update account_usage_windows set used_vie=allowance_vie where id=?", owner.windowId());
        error(send(owner, "no-budget", CONTENT), 400, ErrorCode.INVALID_INPUT);
        verify(provider, never()).generate(any());
        assertThat(requests.count()).isZero();
        assertThat(messages.count()).isZero();
        assertBalances("20000", "0");
    }

    @Test
    @DisplayName("타인 채팅방은 계수·생성·저장 전에 차단한다")
    void deniesForeignRoom() throws Exception {
        error(send(other, owner.roomId(), "foreign-key", CONTENT), 404, ErrorCode.CHAT_ROOM_NOT_FOUND);
        verifyNoInteractions(provider);
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("성공 저장 도중 오류는 상태·제공사 ID·정산·AI 답변을 모두 롤백한다")
    void rollsBackCompletionAsOneTransaction() throws Exception {
        doThrow(new DataIntegrityViolationException("test-only-storage-failure"))
                .when(messageService).create(any(), any(), eq(SenderType.ASSISTANT), anyString());
        error(send(owner, "save-failure", CONTENT), 500, ErrorCode.SERVER_ERROR);
        assertPendingRollback();
        assertThat(data(send(owner, "save-failure", CONTENT), 202).get("status")).isEqualTo("PROCESSING");
        verify(provider, times(1)).generate(any());
        verify(provider, times(1)).countInputTokens(any());
    }

    @Test
    @DisplayName("유효한 최근 20개를 시각·ID 기준으로 골라 과거 순으로 전달한다")
    void selectsRecentTwentyWithStableTieBreak() {
        for (int index = 0; index < 22; index++) {
            seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "history-" + index,
                    readAt.minusSeconds(60), readAt.plusSeconds(60));
        }
        List<String> context = messageService.findRecentContext(owner.userId(), owner.roomId(), readAt)
                .stream().map(message -> message.content()).toList();
        assertThat(context).hasSize(20);
        assertThat(context.getFirst()).isEqualTo("history-2");
        assertThat(context.getLast()).isEqualTo("history-21");
    }

    @Test
    @DisplayName("만료 순간·만료 후·미래 전송·실패·처리 중·타인 메시지를 맥락에서 제외한다")
    void filtersUnavailableContext() {
        seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "visible", readAt.minusSeconds(10), readAt.plusNanos(1000));
        seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "expired-exact", readAt.minusSeconds(10), readAt);
        seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "expired-before", readAt.minusSeconds(10), readAt.minusNanos(1000));
        seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "future", readAt.plusSeconds(10), readAt.plusSeconds(60));
        seed(AiRequestStatus.FAILED, SenderType.USER, "failed", readAt.minusSeconds(10), readAt.plusSeconds(60));
        seed(AiRequestStatus.PROCESSING, SenderType.USER, "processing", readAt.minusSeconds(10), readAt.plusSeconds(60));
        assertThat(messageService.findRecentContext(owner.userId(), owner.roomId(), readAt))
                .extracting(message -> message.content()).containsExactly("visible");
        assertThat(messageService.findRecentContext(other.userId(), owner.roomId(), readAt)).isEmpty();
    }

    @Test
    @DisplayName("생성 중 참고 원문을 삭제해도 새 답변을 저장하고 원문을 복원하지 않는다")
    void keepsAnswerWhenReferenceIsDeletedDuringGeneration() throws Exception {
        Long source = seed(AiRequestStatus.SUCCEEDED, SenderType.USER, "reference-text",
                readAt.minusSeconds(10), readAt.plusSeconds(60));
        doAnswer(invocation -> {
            AiChatRequest input = invocation.getArgument(0);
            assertThat(input.messages()).extracting(AiChatMessage::content).contains("reference-text");
            jdbc.update("delete from messages where id=?", source);
            return new AiChatResult("resp-after-deletion", ANSWER, USAGE);
        }).when(provider).generate(any());
        assertThat(((Map<?, ?>) data(send(owner, "delete-during", CONTENT), 200).get("answer")).get("content")).isEqualTo(ANSWER);
        assertThat(messages.existsById(source)).isFalse();
        assertThat(messages.findAll()).extracting(Message::getContent).doesNotContain("reference-text");
    }

    @Test
    @DisplayName("저장된 사용자 원문을 삭제해도 같은 요청의 완성된 AI 답변은 유지된다")
    void deletingUserMessageDoesNotCascadeToAnswer() throws Exception {
        Map<?, ?> first = data(send(owner, "delete-source", CONTENT), 200);
        jdbc.update("delete from messages where sender='USER'");
        Map<?, ?> status = data(query(owner, UUID.fromString((String) first.get("requestId"))), 200);
        assertThat(((Map<?, ?>) status.get("answer")).get("content")).isEqualTo(ANSWER);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료·삭제된 답변은 재접속과 같은 키 재전송에서도 복원하지 않는다")
    void hidesExpiredAnswerOnReplay() throws Exception {
        Map<?, ?> first = data(send(owner, "expired-answer", CONTENT), 200);
        jdbc.update("update messages set expires_at=sent_at where sender='ASSISTANT'");
        Map<?, ?> replay = data(send(owner, "expired-answer", CONTENT), 200);
        assertThat(replay.get("requestId")).isEqualTo(first.get("requestId"));
        assertThat(replay.get("answer")).isNull();
        assertThat(text(query(owner, UUID.fromString((String) first.get("requestId"))))).doesNotContain(ANSWER);
        verify(provider, times(1)).generate(any());
        assertBalances("20", "0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t"})
    @DisplayName("공백 메시지는 한국어 필드 오류를 반환하고 외부 호출하지 않는다")
    void validatesBlankInput(String content) throws Exception {
        MockHttpServletResponse response = send(owner, "invalid-input", content);
        error(response, 400, ErrorCode.INVALID_INPUT);
        assertThat(text(response)).contains("메시지를 입력해 주세요.");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("입력 2000자는 허용하고 2001자는 제공사 호출 전에 차단한다")
    void validatesLength() throws Exception {
        error(send(owner, "too-long", "가".repeat(2001)), 400, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(provider);
        data(send(owner, "max-length", "가".repeat(2000)), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "null", "[]", "{broken"})
    @DisplayName("누락·잘못된 JSON 본문은 500이 아닌 공통 입력 오류로 처리한다")
    void rejectsMalformedJson(String body) throws Exception {
        error(raw(owner.userId().toString(), owner.roomId(), "bad-json", body), 400, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("필수 멱등 헤더 누락은 외부 호출 없이 400으로 처리한다")
    void rejectsMissingKeyHeader() throws Exception {
        var response = mvc.perform(post("/api/chat-rooms/{id}/messages", owner.roomId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner.userId().toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("content", CONTENT))))
                .andReturn().getResponse();
        error(response, 400, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad key", "한글", "key/invalid"})
    @DisplayName("잘못된 멱등키는 계수와 생성 전에 차단한다")
    void rejectsMalformedKey(String key) throws Exception {
        error(send(owner, key, CONTENT), 400, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("인증 없는 전송은 401이며 채팅 처리를 시작하지 않는다")
    void rejectsAnonymousRequest() throws Exception {
        var response = mvc.perform(post("/api/chat-rooms/{id}/messages", owner.roomId())
                .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("content", CONTENT)))).andReturn().getResponse();
        error(response, 401, ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "0", "-1", "9223372036854775808"})
    @DisplayName("잘못된 회원 식별값을 가진 JWT는 전송 전에 거절한다")
    void rejectsInvalidSubject(String subject) throws Exception {
        error(raw(subject, owner.roomId(), "bad-subject", json.writeValueAsString(Map.of("content", CONTENT))),
                401, ErrorCode.INVALID_TOKEN);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("양수가 아닌 채팅방 식별자는 입력 오류로 처리한다")
    void rejectsInvalidRoomId(long roomId) throws Exception {
        error(send(owner, roomId, "bad-room", CONTENT), 400, ErrorCode.INVALID_INPUT);
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("대화·레브 설정은 응답 메타데이터와 오류 로그에 노출하지 않는다")
    void protectsPrivateTextInLogs(CapturedOutput output) throws Exception {
        data(send(owner, "privacy", CONTENT), 200);
        error(raw(owner.userId().toString(), owner.roomId(), "bad-json", "{\"content\":\"private-invalid-json"),
                400, ErrorCode.INVALID_INPUT);
        assertThat(output.toString()).doesNotContain(CONTENT, ANSWER, PROMPT, "private-invalid-json");
    }

    protected void assertPendingRollback() {
        AiRequest stored = requests.findAll().getFirst();
        assertThat(stored.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(stored.getProviderRequestId()).isNull();
        assertThat(stored.getFinishedAt()).isNull();
        assertThat(stored.getInputTokens()).isNull();
        assertThat(stored.getChargedVie()).isEqualByComparingTo("0");
        assertThat(messages.findAll()).extracting(Message::getSender).containsExactly(SenderType.USER);
        assertBalances("0", "76.2");
    }

    protected void assertBalances(String used, String reserved) {
        AccountUsageWindow window = windows.findById(owner.windowId()).orElseThrow();
        assertThat(window.getUsedVie()).isEqualByComparingTo(used);
        assertThat(window.getReservedVie()).isEqualByComparingTo(reserved);
    }

    protected Owner createOwner(String suffix) {
        return tx().execute(status -> {
            User user = User.of(suffix, suffix + "@example.com", AuthTestFixtures.dummyPasswordHash());
            ReflectionTestUtils.setField(user, "signupCompletedAt", readAt.minus(Duration.ofDays(1)));
            users.saveAndFlush(user);
            Reve reve = reves.saveAndFlush(Reve.of("REVE_" + suffix, "루미", Mbti.INFP, "레브 소개", PROMPT, BigDecimal.ONE));
            UserReve assignment = assignments.saveAndFlush(UserReve.of(user, reve));
            ChatRoom room = rooms.saveAndFlush(ChatRoom.of(assignment));
            AccountUsageWindow window = windows.saveAndFlush(AccountUsageWindow.of(user));
            return new Owner(user.getId(), room.getId(), window.getId());
        });
    }

    protected Long seed(AiRequestStatus state, SenderType sender, String content, Instant sentAt, Instant expiresAt) {
        UUID requestId = requestCommand.reserve(owner.userId(), owner.roomId(), owner.windowId(),
                "seed-" + UUID.randomUUID(), "a".repeat(64), 1000, 1024, Instant.now().plusSeconds(120));
        if (state == AiRequestStatus.SUCCEEDED) requestCommand.completeSuccess(owner.userId(), requestId, USAGE);
        if (state == AiRequestStatus.FAILED) requestCommand.completeFailure(owner.userId(), requestId, ErrorCode.SERVER_ERROR);
        return tx().execute(status -> {
            AiRequest request = requests.findById(requestId).orElseThrow();
            Message message = Message.of(request.getChatRoom(), request, sender, content);
            ReflectionTestUtils.setField(message, "sentAt", sentAt);
            ReflectionTestUtils.setField(message, "expiresAt", expiresAt);
            return messages.saveAndFlush(message).getId();
        });
    }

    protected MockHttpServletResponse send(Owner user, String key, String content) throws Exception {
        return send(user, user.roomId(), key, content);
    }
    protected MockHttpServletResponse send(Owner user, long room, String key, String content) throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return raw(user.userId().toString(), room, key, json.writeValueAsString(Map.of("content", content)));
    }
    protected MockHttpServletResponse raw(String subject, long room, String key, String body) throws Exception {
        return mvc.perform(post("/api/chat-rooms/{id}/messages", room)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subject))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
    protected MockHttpServletResponse query(Owner user, UUID requestId) throws Exception {
        return mvc.perform(get("/api/ai-requests/{id}", requestId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user.userId().toString())))
                .andReturn().getResponse();
    }
    protected String token(String subject) { return JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(subject).build()); }
    protected String text(MockHttpServletResponse response) { return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8); }
    protected Map<?, ?> data(MockHttpServletResponse response, int status) {
        assertThat(response.getStatus()).as(text(response)).isEqualTo(status);
        return (Map<?, ?>) json.readValue(text(response), Map.class).get("data");
    }
    protected void error(MockHttpServletResponse response, int status, ErrorCode code) {
        assertThat(response.getStatus()).as(text(response)).isEqualTo(status);
        assertThat(json.readValue(text(response), Map.class).get("code")).isEqualTo(code.getCode());
    }
    protected TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    protected record Owner(Long userId, Long roomId, Long windowId) { }
}
