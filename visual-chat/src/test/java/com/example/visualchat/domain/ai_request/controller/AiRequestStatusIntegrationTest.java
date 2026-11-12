package com.example.visualchat.domain.ai_request.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiRequestReceipt;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.ai_request.repository.AiRequestRepository;
import com.example.visualchat.domain.ai_request.service.AiRequestAdmissionCommand;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 JWT로 상태 조회의 소유권·만료 경계·삭제 후 원문 차단과 재전송 불변성을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-request-status-issue38;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@Import(AiRequestStatusIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AiRequestStatusIntegrationTest {
    private static final String KEY = "private-request-key";
    private static final String CONTENT = "노출하지 않을 사용자 질문";
    private static final String ANSWER = "저장된 AI 답변";
    private static final AiTokenUsage USAGE = new AiTokenUsage(1000, 0, 0, 0, 200);
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtEncoder encoder;
    @Autowired private AiRequestAdmissionCommand admission;
    @Autowired private AiRequestRepository requests;
    @Autowired private MessageRepository messages;
    @Autowired private AccountUsageWindowRepository windows;
    @Autowired private UserRepository users;
    @Autowired private ReveRepository reves;
    @Autowired private UserReveRepository assignments;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TestClock clock;
    @MockitoSpyBean private AiRequestCommand writeCommand;
    @MockitoSpyBean private MessageService messageService;
    private Owner owner;
    private Owner other;
    private UUID requestId;
    private Instant readAt;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock statusTestClock() {
            return new TestClock(new AtomicReference<>(Instant.EPOCH), ZoneOffset.UTC);
        }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> time;
        private final ZoneId zone;
        TestClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
        void set(Instant instant) { time.set(instant); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new TestClock(time, zone); }
        @Override public Instant instant() { return time.get(); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareOwnedRequests() {
        // [CAUTION] 이 클래스의 전용 메모리 DB만 정리
        for (String table : new String[]{"messages", "ai_requests", "chat_rooms", "user_reves", "account_usage_windows", "users", "reves"}) {
            jdbc.update("delete from " + table);
        }
        readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock.set(readAt);
        owner = createOwner("owner");
        other = createOwner("other");
        requestId = admission.admit(owner.userId(), owner.roomId(), KEY, CONTENT, 1000, 500, Instant.now().plusSeconds(120)).requestId();
        clearInvocations(writeCommand, messageService);
    }

    @ParameterizedTest
    @EnumSource(AiRequestStatus.class)
    @DisplayName("본인 요청의 모든 처리 상태는 조회 성공 200으로 반환하며 상태 자체는 변경하지 않는다")
    void returnsStoredStateWithoutMutation(AiRequestStatus expected) throws Exception {
        complete(expected);
        if (expected != AiRequestStatus.PROCESSING) {
            addMessage(SenderType.ASSISTANT, ANSWER, readAt.plusSeconds(60));
        }
        AiRequest before = requests.findById(requestId).orElseThrow();
        AccountUsageWindow balanceBefore = windows.findById(owner.windowId()).orElseThrow();
        clearInvocations(writeCommand, messageService);
        MockHttpServletResponse response = query(owner.userId(), requestId);
        assertThat(response.getStatus()).isEqualTo(200);
        Map<?, ?> result = data(response);
        assertThat(result.get("status")).isEqualTo(expected.name());
        assertThat(result.get("requestId")).isEqualTo(requestId.toString());
        assertThat(((Number) result.get("chatRoomId")).longValue()).isEqualTo(owner.roomId());
        assertThat(result.get("answer") != null).isEqualTo(expected == AiRequestStatus.SUCCEEDED);
        assertThat(requests.findById(requestId).orElseThrow().getVersion()).isEqualTo(before.getVersion());
        assertThat(windows.findById(owner.windowId()).orElseThrow().getVersion()).isEqualTo(balanceBefore.getVersion());
        verifyNoInteractions(writeCommand);
        if (expected != AiRequestStatus.SUCCEEDED) {
            verifyNoInteractions(messageService);
        }
    }

    @Test
    @DisplayName("성공 응답은 유효한 AI 답변만 포함하고 키·지문·사용자 원문·서버 설정을 노출하지 않는다")
    void returnsOnlyVisibleAssistantAnswerAndPublicMetadata() throws Exception {
        complete(AiRequestStatus.SUCCEEDED);
        addMessage(SenderType.USER, CONTENT, readAt.plusSeconds(60));
        Long answerId = addMessage(SenderType.ASSISTANT, ANSWER, readAt.plusSeconds(60));
        MockHttpServletResponse response = query(owner.userId(), requestId);
        Map<?, ?> result = data(response);
        Map<?, ?> answer = (Map<?, ?>) result.get("answer");
        assertThat(answer.get("content")).isEqualTo(ANSWER);
        assertThat(answer.get("sender")).isEqualTo("ASSISTANT");
        assertThat(((Number) answer.get("messageId")).longValue()).isEqualTo(answerId);
        assertThat(Instant.parse((String) answer.get("expiresAt"))).isEqualTo(readAt.plusSeconds(60));
        assertThat(response.getContentAsString()).doesNotContain(KEY, CONTENT, "requestFingerprint", "idempotencyKey",
                "providerRequestId", "systemPrompt", "비공개 서버 설정", "passwordHash", "newlyCreated");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    @DisplayName("만료 직전까지 답변을 조회하고 만료 시각과 같거나 지난 원문은 물리 삭제 전에도 차단한다")
    void enforcesExactExpiryBoundary(long micros) throws Exception {
        complete(AiRequestStatus.SUCCEEDED);
        Long messageId = addMessage(SenderType.ASSISTANT, ANSWER, readAt.plus(micros, ChronoUnit.MICROS));
        MockHttpServletResponse response = query(owner.userId(), requestId);
        Map<?, ?> result = data(response);
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(result.get("answer") != null).isEqualTo(micros > 0);
        if (micros <= 0) {
            assertThat(response.getContentAsString()).doesNotContain(ANSWER);
        }
        assertThat(messages.findById(messageId)).isPresent();
        assertThat(requests.findById(requestId).orElseThrow().getChargedVie()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("답변을 삭제한 뒤 조회하거나 같은 키로 재전송해도 원문과 예약을 복원하지 않는다")
    void neverRestoresDeletedAnswerOnReadOrReplay() throws Exception {
        complete(AiRequestStatus.SUCCEEDED);
        Long answerId = addMessage(SenderType.ASSISTANT, ANSWER, readAt.plusSeconds(60));
        messages.deleteById(answerId);
        clearInvocations(writeCommand);

        // [WHY] 기존 접수 재사용은 달라진 서버 예산이나 처리 기한으로 새 요청을 만들지 않음
        AiRequestReceipt replay = admission.admit(owner.userId(), owner.roomId(), KEY, CONTENT, -1, -1, Instant.EPOCH);
        assertThat(replay.requestId()).isEqualTo(requestId);
        assertThat(replay.newlyCreated()).isFalse();
        MockHttpServletResponse response = query(owner.userId(), requestId);
        assertThat(data(response).get("answer")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(ANSWER, CONTENT);
        assertThat(messages.count()).isZero();
        assertThat(requests.count()).isEqualTo(1);
        AccountUsageWindow window = windows.findById(owner.windowId()).orElseThrow();
        assertThat(window.getUsedVie()).isEqualByComparingTo("20");
        assertThat(window.getReservedVie()).isZero();
        verifyNoInteractions(writeCommand);
    }

    @Test
    @DisplayName("참고 사용자 원문을 삭제해도 이미 완성된 유효 답변은 조회할 수 있다")
    void keepsCompletedAnswerAfterSourceDeletion() throws Exception {
        complete(AiRequestStatus.SUCCEEDED);
        Long source = addMessage(SenderType.USER, CONTENT, readAt.plusSeconds(60));
        addMessage(SenderType.ASSISTANT, ANSWER, readAt.plusSeconds(60));
        messages.deleteById(source);
        assertThat(((Map<?, ?>) data(query(owner.userId(), requestId)).get("answer")).get("content")).isEqualTo(ANSWER);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자 메시지만 남아 있으면 이를 AI 답변으로 반환하지 않는다")
    void neverSubstitutesUserMessageForAnswer() throws Exception {
        complete(AiRequestStatus.SUCCEEDED);
        addMessage(SenderType.USER, CONTENT, readAt.plusSeconds(60));
        MockHttpServletResponse response = query(owner.userId(), requestId);
        assertThat(data(response).get("answer")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(CONTENT);
    }

    @Test
    @DisplayName("처리 기한이 지나도 PROCESSING과 예약량을 유지하며 조회가 실패 처리하지 않는다")
    void doesNotFinalizeUnknownOutcomeAfterDeadline() throws Exception {
        Instant past = readAt.minusSeconds(120);
        jdbc.update("update ai_requests set started_at = ?, processing_deadline_at = ? where id = ?",
                Timestamp.from(past), Timestamp.from(past.plusSeconds(60)), requestId);
        MockHttpServletResponse response = query(owner.userId(), requestId);
        assertThat(data(response).get("status")).isEqualTo("PROCESSING");
        assertThat(requests.findById(requestId).orElseThrow().getFinishedAt()).isNull();
        assertThat(windows.findById(owner.windowId()).orElseThrow().getReservedVie()).isEqualByComparingTo("50");
        verifyNoInteractions(writeCommand, messageService);
    }

    @Test
    @DisplayName("재접속과 다른 기기의 반복 조회는 새 실행·예약·메시지 생성을 하지 않는다")
    void repeatedReadsDoNotRestartProcessing() throws Exception {
        for (String device : new String[]{"phone", "tablet", "reconnected-phone"}) {
            mockMvc.perform(get("/api/ai-requests/{requestId}", requestId)
                            .session(new MockHttpSession()).header(HttpHeaders.USER_AGENT, device)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner.userId().toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"));
        }
        verifyNoInteractions(writeCommand);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(messages.count()).isZero();
        assertThat(windows.findById(owner.windowId()).orElseThrow().getReservedVie()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("타인 요청은 답변 조회 전에 404로 차단한다")
    void deniesForeignRequestBeforeMessageRead() throws Exception {
        assertError(query(other.userId(), requestId), 404, ErrorCode.AI_REQUEST_NOT_FOUND);
        verifyNoInteractions(messageService, writeCommand);
    }

    @Test
    @DisplayName("존재하지 않는 요청은 404를 반환한다")
    void rejectsMissingRequest() throws Exception {
        assertError(query(owner.userId(), UUID.randomUUID()), 404, ErrorCode.AI_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("인증되지 않은 요청은 상태 조회 전에 401로 차단한다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/ai-requests/{requestId}", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.getCode()));
        verifyNoInteractions(messageService, writeCommand);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-notauuid0000"})
    @DisplayName("형식이 잘못된 요청 UUID는 공통 입력 오류 400으로 반환한다")
    void rejectsMalformedUuid(String value) throws Exception {
        mockMvc.perform(get("/api/ai-requests/{requestId}", value)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(owner.userId().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "0", "-1", "9223372036854775808"})
    @DisplayName("서명은 유효해도 회원 식별값이 잘못된 JWT는 인증 오류로 처리한다")
    void rejectsInvalidJwtSubject(String subject) throws Exception {
        mockMvc.perform(get("/api/ai-requests/{requestId}", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subject)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_TOKEN.getCode()));
    }

    @Test
    @DisplayName("멱등 접수 로그에는 원문을 남기지 않고 요청 기록에는 지문만 저장한다")
    void doesNotDuplicateRawContentInTrackingOrLogs(CapturedOutput output) {
        String privateContent = "비공개-원문-" + UUID.randomUUID();
        AiRequestReceipt result = admission.admit(owner.userId(), owner.roomId(), "another-key", privateContent,
                1000, 500, Instant.now().plusSeconds(120));
        AiRequest saved = requests.findById(result.requestId()).orElseThrow();
        assertThat(saved.getRequestFingerprint()).matches("[0-9a-f]{64}").doesNotContain(privateContent);
        assertThat(result.toString()).doesNotContain(privateContent);
        assertThat(output.toString()).doesNotContain(privateContent);
        assertThat(messages.count()).isZero();
    }

    private Owner createOwner(String suffix) {
        return tx().execute(status -> {
            User user = User.of(suffix, suffix + "@example.com", AuthTestFixtures.dummyPasswordHash());
            ReflectionTestUtils.setField(user, "signupCompletedAt", readAt.minus(Duration.ofDays(1)));
            users.saveAndFlush(user);
            Reve reve = reves.saveAndFlush(Reve.of("REVE_" + suffix, "루미", Mbti.INFP, "소개", "비공개 서버 설정", BigDecimal.ONE));
            UserReve assigned = assignments.saveAndFlush(UserReve.of(user, reve));
            ChatRoom room = rooms.saveAndFlush(ChatRoom.of(assigned));
            AccountUsageWindow window = windows.saveAndFlush(AccountUsageWindow.of(user));
            return new Owner(user.getId(), room.getId(), window.getId());
        });
    }

    private Long addMessage(SenderType sender, String content, Instant expiresAt) {
        return tx().execute(status -> {
            AiRequest request = requests.findById(requestId).orElseThrow();
            Message message = Message.of(request.getChatRoom(), request, sender, content);
            // [WHY] 시간을 기다리지 않고 저장된 만료 시각과 조회 시각의 정확한 경계를 검증
            ReflectionTestUtils.setField(message, "sentAt", expiresAt.minus(Duration.ofDays(90)));
            ReflectionTestUtils.setField(message, "expiresAt", expiresAt);
            return messages.saveAndFlush(message).getId();
        });
    }

    private void complete(AiRequestStatus status) {
        if (status == AiRequestStatus.SUCCEEDED) {
            writeCommand.completeSuccess(owner.userId(), requestId, USAGE);
        } else if (status == AiRequestStatus.FAILED) {
            writeCommand.completeFailure(owner.userId(), requestId, ErrorCode.SERVER_ERROR);
        }
    }

    private MockHttpServletResponse query(Long userId, UUID id) throws Exception {
        return mockMvc.perform(get("/api/ai-requests/{requestId}", id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId.toString())))
                .andReturn().getResponse();
    }

    private String token(String subject) {
        return JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(subject).build());
    }

    private Map<?, ?> data(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return (Map<?, ?>) mapper.readValue(response.getContentAsString(), Map.class).get("data");
    }

    private void assertError(MockHttpServletResponse response, int status, ErrorCode error) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(mapper.readValue(response.getContentAsString(), Map.class).get("code")).isEqualTo(error.getCode());
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private record Owner(Long userId, Long roomId, Long windowId) { }
}
