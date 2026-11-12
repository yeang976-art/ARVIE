package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowService;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.ai_request.repository.AiRequestRepository;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.repository.ChatRoomRepository;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** [ROLE] 실제 PostgreSQL 트랜잭션에서 예약·정산 원자성, 동시 충돌, 주기 변경과 DB 제약을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER,
        "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
class PostgresAiRequestSettlementTest {
    private static final AiTokenUsage USAGE = new AiTokenUsage(1000, 0, 0, 0, 200);
    @Autowired private AiRequestCommand command;
    @Autowired private AiRequestRepository requests;
    @Autowired private AccountUsageWindowRepository windows;
    @Autowired private UserRepository users;
    @Autowired private ReveRepository reves;
    @Autowired private UserReveRepository assignments;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AiRequestService requestService;
    @MockitoSpyBean private AccountUsageWindowService windowService;
    private Owner owner;

    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresAiRequestSettlementTest::testUrl);
        registry.add("spring.datasource.username", () -> testSetting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> testSetting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 실제 개발·운영 DB에서 스키마 생성과 정리가 실행되지 않도록 URL을 제한
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/(arvie_signup_test|arvie_test)")) {
            throw new IllegalStateException("전용 로컬 PostgreSQL 테스트 DB URL만 사용할 수 있습니다.");
        }
        return url;
    }

    private static String testSetting(String localName, String ciName) {
        String explicit = System.getenv(localName);
        String value = explicit != null ? explicit : System.getenv(ciName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PostgreSQL 테스트 연결 설정이 필요합니다.");
        }
        return value;
    }

    @BeforeEach
    void prepareIsolatedDatabase() {
        reset(requestService, windowService);
        dropFailureTriggers();
        jdbc.execute("truncate table messages, ai_requests, chat_rooms, user_reves, account_usage_windows, users, reves restart identity cascade");
        owner = createOwner("first");
    }

    @Test
    @DisplayName("예약량과 요청을 함께 커밋하고 성공 시 실제 비용 및 상세 사용량을 함께 저장한다")
    void commitsReservationAndDetailedSettlementTogether() {
        UUID requestId = reserve("first-key");
        AiRequest pending = requests.findById(requestId).orElseThrow();
        assertThat(pending.getReservedVie()).isEqualByComparingTo("50");
        assertThat(pending.getMeteringVersion()).isEqualTo("terra-v1");
        assertThat(pending.getInputTokens()).isNull();
        assertBalances("0", "50");

        command.completeSuccess(owner.userId(), requestId, new AiTokenUsage(1000, 100, 200, 100, 200));
        AiRequest completed = requests.findById(requestId).orElseThrow();
        // [NOTE] 일반 입력 600 + 캐시 읽기 100 + 30분 쓰기 200 + 12시간 쓰기 100 + 출력 200
        assertThat(completed.getChargedVie()).isEqualByComparingTo("21.05");
        assertThat(completed.getStatus()).isEqualTo(AiRequestStatus.SUCCEEDED);
        assertThat(completed.getCachedInputTokens()).isEqualTo(100L);
        assertThat(completed.getCacheWrite30mTokens()).isEqualTo(200L);
        assertThat(completed.getCacheWrite12hTokens()).isEqualTo(100L);
        assertThat(completed.getInputTokens()).isEqualTo(1000L);
        assertThat(completed.getOutputTokens()).isEqualTo(200L);
        assertBalances("21.05", "0");
    }

    @Test
    @DisplayName("확정 실패는 다른 요청의 예약을 보존하면서 해당 요청의 예약만 해제한다")
    void releasesOnlyFailedRequestReservation() {
        UUID failed = reserve("failed-key");
        UUID other = reserve("other-key");
        command.completeFailure(owner.userId(), failed, ErrorCode.SERVER_ERROR);
        assertBalances("0", "50");
        assertThat(requests.findById(failed).orElseThrow().getChargedVie()).isZero();
        assertThat(requests.findById(failed).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(requests.findById(other).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
    }

    @Test
    @DisplayName("잔여량 부족은 요청 저장 전에 거절하고 예약량을 변경하지 않는다")
    void rejectsInsufficientBalanceBeforeRequestCreation() {
        jdbc.update("update account_usage_windows set allowance_vie = 49.999999 where id = ?", owner.windowId());
        assertServiceError(ErrorCode.INVALID_INPUT, () -> reserve("key"));
        assertThat(requests.count()).isZero();
        assertBalances("0", "0");
    }

    @Test
    @DisplayName("멱등키 저장 충돌이 발생하면 증가시킨 예약량도 함께 롤백한다")
    void rollsBackReservationOnDuplicateRequestInsert() {
        UUID existing = reserve("same-key");
        assertThatThrownBy(() -> reserve("same-key")).isInstanceOf(DataIntegrityViolationException.class);
        assertBalances("0", "50");
        assertThat(requests.findAll()).extracting(AiRequest::getId).containsExactly(existing);
    }

    @Test
    @DisplayName("요청 INSERT의 DB 오류가 예약량 변경을 함께 롤백한다")
    void rollsBackReservationOnDatabaseFailure() {
        installFailure("ai_requests", "vie_test_request_failure");
        try {
            assertThatThrownBy(() -> reserve("key")).isInstanceOf(DataIntegrityViolationException.class);
            assertBalances("0", "0");
            assertThat(requests.count()).isZero();
        } finally {
            dropFailureTriggers();
        }
    }

    @Test
    @DisplayName("잔액 저장 실패는 성공 상태·차감·사용량을 모두 롤백하고 재정산을 허용한다")
    void rollsBackCompletionWhenBalanceUpdateFails() {
        UUID requestId = reserve("key");
        installFailure("account_usage_windows", "vie_test_window_failure");
        try {
            assertThatThrownBy(() -> command.completeSuccess(owner.userId(), requestId, USAGE))
                    .isInstanceOf(DataIntegrityViolationException.class);
            AiRequest unchanged = requests.findById(requestId).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
            assertThat(unchanged.getChargedVie()).isZero();
            assertThat(unchanged.getInputTokens()).isNull();
            assertThat(unchanged.getFinishedAt()).isNull();
            assertBalances("0", "50");
        } finally {
            dropFailureTriggers();
        }
        command.completeSuccess(owner.userId(), requestId, USAGE);
        assertBalances("20", "0");
    }

    @Test
    @DisplayName("실패 예약 해제의 DB 오류도 요청 상태와 잔액을 함께 롤백한다")
    void rollsBackFailureWhenReleaseUpdateFails() {
        UUID requestId = reserve("key");
        installFailure("account_usage_windows", "vie_test_window_failure");
        try {
            assertThatThrownBy(() -> command.completeFailure(owner.userId(), requestId, ErrorCode.SERVER_ERROR))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
            assertBalances("0", "50");
        } finally {
            dropFailureTriggers();
        }
    }

    @Test
    @DisplayName("예약 초과 사용은 비용을 잘라 차감하지 않고 요청과 예약을 처리 중으로 보존한다")
    void keepsReservationOnExcessiveActualUsage() {
        UUID requestId = reserve("key");
        assertServiceError(ErrorCode.INVALID_INPUT,
                () -> command.completeSuccess(owner.userId(), requestId, new AiTokenUsage(1000, 0, 0, 0, 1000)));
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertBalances("0", "50");
    }

    @ParameterizedTest
    @CsvSource({"provider,OTHER", "model_name,other-model", "metering_version,terra-v2"})
    @DisplayName("알 수 없는 제공자·모델·정책을 현재 요율로 임의 정산하지 않는다")
    void rejectsUnknownMeteringPolicy(String column, String value) {
        UUID requestId = reserve("key");
        jdbc.update("update ai_requests set " + column + " = ? where id = ?", value, requestId);
        assertServiceError(ErrorCode.SERVER_ERROR, () -> command.completeSuccess(owner.userId(), requestId, USAGE));
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertBalances("0", "50");
    }

    @Test
    @DisplayName("다른 회원은 요청을 완료하거나 자기 잔액으로 정산할 수 없다")
    void rejectsForeignRequestCompletion() {
        UUID requestId = reserve("key");
        Owner other = createOwner("second");
        assertServiceError(ErrorCode.AI_REQUEST_NOT_FOUND, () -> command.completeSuccess(other.userId(), requestId, USAGE));
        assertServiceError(ErrorCode.AI_REQUEST_NOT_FOUND, () -> command.completeFailure(other.userId(), requestId, ErrorCode.SERVER_ERROR));
        assertBalances("0", "50");
        assertThat(windows.findById(other.windowId()).orElseThrow().getReservedVie()).isZero();
    }

    @Test
    @DisplayName("서버가 확보한 주기가 요청 시작 전에 만료되면 이전 주기에 예약하지 않는다")
    void rejectsReservationInExpiredPeriod() {
        makeWindowPrevious();
        assertServiceError(ErrorCode.USAGE_PERIOD_CHANGED, () -> reserve("key"));
        assertBalances("0", "0");
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("주기가 바뀐 뒤 완료해도 원래 예약 주기에만 정산하고 새 주기는 보존한다")
    void settlesOriginalWindowAfterRollover() {
        UUID requestId = reserve("key");
        Instant previousEnd = makeWindowPrevious();
        jdbc.update("update ai_requests set started_at = ? where id = ?", Timestamp.from(previousEnd.minusSeconds(1)), requestId);
        Long currentId = tx().execute(status -> {
            User user = users.findById(owner.userId()).orElseThrow();
            return windows.saveAndFlush(AccountUsageWindow.of(user, UsagePeriod.from(user.getSignupCompletedAt(), Instant.now()))).getId();
        });

        command.completeSuccess(owner.userId(), requestId, USAGE);
        assertBalances("20", "0");
        AccountUsageWindow current = windows.findById(currentId).orElseThrow();
        assertThat(current.getUsedVie()).isZero();
        assertThat(current.getReservedVie()).isZero();
        assertThat(requests.findById(requestId).orElseThrow().getUsageWindowId()).isEqualTo(owner.windowId());
    }

    @Test
    @DisplayName("처리 기한 경과만으로 실패·예약 해제하지 않으며 확인된 성공은 나중에 정산한다")
    void keepsUnknownOutcomeReservedUntilConfirmed() {
        UUID requestId = reserve("key");
        Instant past = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_requests set started_at = ?, processing_deadline_at = ? where id = ?", Timestamp.from(past), Timestamp.from(past.plusSeconds(60)), requestId);
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertBalances("0", "50");
        command.completeSuccess(owner.userId(), requestId, USAGE);
        assertBalances("20", "0");
    }

    @Test
    @DisplayName("같은 버전으로 동시에 예약해도 한도 초과분과 패배한 요청 INSERT는 함께 롤백된다")
    void concurrentReservationsCannotOverspend() throws Exception {
        jdbc.update("update account_usage_windows set allowance_vie = 1000 where id = ?", owner.windowId());
        blockBothWindowReads();
        List<Outcome> outcomes = runBoth(
                () -> reserve("first-key", 20000, 4000),
                () -> reserve("second-key", 20000, 4000)
        );
        assertOneOptimisticConflict(outcomes);
        assertBalances("0", "700");
        assertThat(requests.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("동시 중복 성공 또는 성공·실패 경합에서도 한 결과와 한 번의 정산만 커밋된다")
    void concurrentCompletionsSettleExactlyOnce(boolean opposite) throws Exception {
        UUID requestId = reserve("key");
        blockBothRequestReads();
        List<Outcome> outcomes = runBoth(
                () -> { command.completeSuccess(owner.userId(), requestId, USAGE); return requestId; },
                () -> {
                    if (opposite) {
                        command.completeFailure(owner.userId(), requestId, ErrorCode.SERVER_ERROR);
                    } else {
                        command.completeSuccess(owner.userId(), requestId, USAGE);
                    }
                    return requestId;
                }
        );
        reset(requestService);
        assertOneOptimisticConflict(outcomes);
        AiRequest saved = requests.findById(requestId).orElseThrow();
        assertBalances(saved.getStatus() == AiRequestStatus.SUCCEEDED ? "20" : "0", "0");
        assertThat(saved.getChargedVie()).isEqualByComparingTo(saved.getStatus() == AiRequestStatus.SUCCEEDED ? "20" : "0");
        assertServiceError(ErrorCode.INVALID_AI_REQUEST_STATUS,
                () -> command.completeSuccess(owner.userId(), requestId, USAGE));
        assertServiceError(ErrorCode.INVALID_AI_REQUEST_STATUS,
                () -> command.completeFailure(owner.userId(), requestId, ErrorCode.SERVER_ERROR));
    }

    @Test
    @DisplayName("서로 다른 요청의 동시 정산 충돌은 전부 롤백하고 새 트랜잭션에서 결과만 재적용할 수 있다")
    void retriesOnlySettlementAfterConcurrentBalanceConflict() throws Exception {
        UUID first = reserve("first-key");
        UUID second = reserve("second-key");
        blockBothWindowReads();
        List<Outcome> outcomes = runBoth(
                () -> { command.completeSuccess(owner.userId(), first, USAGE); return first; },
                () -> { command.completeSuccess(owner.userId(), second, USAGE); return second; }
        );
        reset(windowService);
        assertOneOptimisticConflict(outcomes);
        assertBalances("20", "50");
        UUID pending = requests.findById(first).orElseThrow().getStatus() == AiRequestStatus.PROCESSING ? first : second;
        // [WHY] AI를 다시 호출하지 않고 이미 받은 사용량으로 정산 트랜잭션만 재시도
        command.completeSuccess(owner.userId(), pending, USAGE);
        assertBalances("40", "0");
        assertThat(requests.findAll()).extracting(AiRequest::getStatus).containsOnly(AiRequestStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cached_input_tokens", "cache_write_30m_tokens", "cache_write_12h_tokens"})
    @DisplayName("직접 SQL로도 캐시 사용량의 음수를 저장할 수 없어야 한다")
    void databaseRejectsNegativeCacheTokens(String column) {
        UUID requestId = reserve("key");
        assertDatabaseCheck(() -> jdbc.update("update ai_requests set " + column + " = -1 where id = ?", requestId));
    }

    @ParameterizedTest
    @CsvSource({"1000,1001,0,0", "1000,500,501,0", "1000,500,250,251"})
    @DisplayName("직접 SQL로도 입력보다 큰 캐시 분류 합계를 저장할 수 없어야 한다")
    void databaseRejectsCachePartitionAboveInput(long input, long cached, long write30m, long write12h) {
        UUID requestId = reserve("key");
        assertDatabaseCheck(() -> jdbc.update("""
                update ai_requests set input_tokens = ?, cached_input_tokens = ?,
                cache_write_30m_tokens = ?, cache_write_12h_tokens = ? where id = ?
                """, input, cached, write30m, write12h, requestId));
    }

    @Test
    @DisplayName("직접 SQL로도 요청의 예약 상한을 초과한 성공 차감은 저장할 수 없어야 한다")
    void databaseRejectsChargeAboveReservation() {
        UUID requestId = reserve("key");
        assertDatabaseCheck(() -> jdbc.update("""
                update ai_requests set status = 'SUCCEEDED', finished_at = started_at,
                charged_vie = reserved_vie + 0.000001 where id = ?
                """, requestId));
    }

    private Owner createOwner(String suffix) {
        return tx().execute(status -> {
            User user = User.of("owner-" + suffix, suffix + "@example.com", AuthTestFixtures.dummyPasswordHash());
            ReflectionTestUtils.setField(user, "signupCompletedAt", Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));
            users.saveAndFlush(user);
            Reve reve = reves.saveAndFlush(Reve.of("REVE_" + suffix, "루미", Mbti.INFP, "소개", "설정", BigDecimal.ONE));
            UserReve assignment = assignments.saveAndFlush(UserReve.of(user, reve));
            ChatRoom room = rooms.saveAndFlush(ChatRoom.of(assignment));
            AccountUsageWindow window = windows.saveAndFlush(AccountUsageWindow.of(user));
            return new Owner(user.getId(), room.getId(), window.getId());
        });
    }

    private UUID reserve(String key) {
        return reserve(key, 1000, 500);
    }

    private UUID reserve(String key, long input, long output) {
        return command.reserve(owner.userId(), owner.roomId(), owner.windowId(), key, "a".repeat(64), input, output,
                Instant.now().plusSeconds(120));
    }

    private Instant makeWindowPrevious() {
        Instant start = Instant.now().minus(Duration.ofDays(31)).truncatedTo(ChronoUnit.MICROS);
        Instant end = start.plus(Duration.ofDays(30));
        jdbc.update("update users set signup_completed_at = ? where id = ?", Timestamp.from(start), owner.userId());
        jdbc.update("update account_usage_windows set period_start_at = ?, period_end_at = ? where id = ?", Timestamp.from(start), Timestamp.from(end), owner.windowId());
        return end;
    }

    private void assertBalances(String used, String reserved) {
        AccountUsageWindow window = windows.findById(owner.windowId()).orElseThrow();
        assertThat(window.getUsedVie()).isEqualByComparingTo(used);
        assertThat(window.getReservedVie()).isEqualByComparingTo(reserved);
        assertThat(window.getUsedVie().add(window.getReservedVie())).isLessThanOrEqualTo(window.getAllowanceVie());
    }

    private void installFailure(String table, String name) {
        jdbc.execute("create function " + name + "() returns trigger language plpgsql as $$ begin raise exception using errcode = '23514', message = '테스트용 정산 저장 실패'; end; $$");
        jdbc.execute("create trigger " + name + " before insert or update on " + table + " for each row execute function " + name + "()");
    }

    private void dropFailureTriggers() {
        jdbc.execute("drop trigger if exists vie_test_request_failure on ai_requests");
        jdbc.execute("drop function if exists vie_test_request_failure()");
        jdbc.execute("drop trigger if exists vie_test_window_failure on account_usage_windows");
        jdbc.execute("drop function if exists vie_test_window_failure()");
    }

    private void blockBothWindowReads() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object window = invocation.callRealMethod();
            barrier.await(15, TimeUnit.SECONDS);
            return window;
        }).when(windowService).getOwnedWindow(any(), any());
    }

    private void blockBothRequestReads() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object request = invocation.callRealMethod();
            barrier.await(15, TimeUnit.SECONDS);
            return request;
        }).when(requestService).getOwnedRequest(any(), any());
    }

    private List<Outcome> runBoth(Callable<UUID> first, Callable<UUID> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> attempt(first));
            var right = executor.submit(() -> attempt(second));
            return List.of(left.get(25, TimeUnit.SECONDS), right.get(25, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Outcome attempt(Callable<UUID> operation) {
        try {
            return new Outcome(operation.call(), null);
        } catch (Exception exception) {
            return new Outcome(null, exception);
        }
    }

    private void assertOneOptimisticConflict(List<Outcome> outcomes) {
        assertThat(outcomes.stream().filter(result -> result.error() == null).count()).isEqualTo(1);
        Throwable failure = outcomes.stream().filter(result -> result.error() != null).findFirst().orElseThrow().error();
        boolean optimistic = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) {
                optimistic = true;
            }
        }
        assertThat(optimistic).as("낙관적 락 충돌이어야 함: %s", failure).isTrue();
    }

    private void assertServiceError(ErrorCode code, Runnable operation) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private void assertDatabaseCheck(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause != null && !(cause instanceof SQLException)) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private record Owner(Long userId, Long roomId, Long windowId) { }
    private record Outcome(UUID requestId, Throwable error) { }
}
