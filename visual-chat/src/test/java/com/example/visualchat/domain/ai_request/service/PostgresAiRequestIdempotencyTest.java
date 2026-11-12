package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiRequestReceipt;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import com.example.visualchat.domain.ai_request.repository.AiRequestRepository;
import com.example.visualchat.domain.ai_request.util.AiRequestFingerprintGenerator;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** [ROLE] PostgreSQL에서 동시 멱등 접수·롤백 후 재조회·잔액 부족 경합과 단일 신규 플래그를 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=8",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresAiRequestIdempotencyTest {
    private static final String KEY = "same-key";
    private static final String CONTENT = "같은 사용자 요청";
    private static final AiTokenUsage USAGE = new AiTokenUsage(1000, 0, 0, 0, 200);
    @Autowired private AiRequestAdmissionCommand admission;
    @Autowired private AiRequestCommand reservation;
    @Autowired private AiRequestRepository requests;
    @Autowired private AccountUsageWindowRepository windows;
    @Autowired private UserRepository users;
    @Autowired private ReveRepository reves;
    @Autowired private UserReveRepository assignments;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private AiRequestFingerprintGenerator generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AiRequestService requestService;
    private Owner owner;

    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresAiRequestIdempotencyTest::testUrl);
        registry.add("spring.datasource.username", () -> testSetting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> testSetting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 스키마를 정리하므로 지정된 로컬 테스트 DB만 허용
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
        reset(requestService);
        jdbc.execute("drop trigger if exists request_idempotency_gate on ai_requests");
        jdbc.execute("drop function if exists request_idempotency_gate()");
        jdbc.execute("drop trigger if exists request_idempotency_failure on ai_requests");
        jdbc.execute("drop function if exists request_idempotency_failure()");
        jdbc.execute("truncate table messages, ai_requests, chat_rooms, user_reves, account_usage_windows, users, reves restart identity cascade");
        owner = createOwner("owner");
    }

    @ParameterizedTest
    @EnumSource(AiRequestStatus.class)
    @DisplayName("잔여량이 0이어도 같은 키·지문의 재전송은 기존 상태와 요청으로 연결된다")
    void replaysExistingRequestRegardlessOfCurrentQuota(AiRequestStatus state) {
        AiRequestReceipt first = admit(KEY, CONTENT);
        if (state == AiRequestStatus.SUCCEEDED) {
            reservation.completeSuccess(owner.userId(), first.requestId(), USAGE);
        } else if (state == AiRequestStatus.FAILED) {
            reservation.completeFailure(owner.userId(), first.requestId(), ErrorCode.SERVER_ERROR);
        }
        jdbc.update("update account_usage_windows set allowance_vie = used_vie + reserved_vie where id = ?", owner.windowId());
        AccountUsageWindow before = windows.findById(owner.windowId()).orElseThrow();
        AiRequestReceipt replay = admission.admit(owner.userId(), owner.roomId(), KEY, CONTENT, -1, -1, Instant.EPOCH);
        assertThat(first.newlyCreated()).isTrue();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.status()).isEqualTo(state);
        assertThat(replay.newlyCreated()).isFalse();
        assertThat(requests.count()).isEqualTo(1);
        assertThat(windows.findById(owner.windowId()).orElseThrow().getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    @DisplayName("같은 문장도 다른 키로 보냈다면 별도 요청 두 건과 예약 두 건으로 처리한다")
    void acceptsIntentionalRepeatWithDifferentKeys() {
        AiRequestReceipt first = admit("first-key", CONTENT);
        AiRequestReceipt second = admit("second-key", CONTENT);
        assertThat(first.newlyCreated()).isTrue();
        assertThat(second.newlyCreated()).isTrue();
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
        assertThat(requests.count()).isEqualTo(2);
        assertReserved("100");
    }

    @Test
    @DisplayName("DB 조회에서도 키의 대소문자를 구분하고 값을 정규화하지 않는다")
    void keepsDatabaseKeysCaseSensitive() {
        assertThat(admit("Case-Key", CONTENT).requestId()).isNotEqualTo(admit("case-key", CONTENT).requestId());
        assertThat(requests.count()).isEqualTo(2);
        assertReserved("100");
    }

    @Test
    @DisplayName("같은 회원·키에 다른 내용은 409 오류로 거절하고 기존 예약을 보존한다")
    void rejectsKeyReuseWithDifferentContent() {
        admit(KEY, CONTENT);
        assertConflict(() -> admit(KEY, CONTENT + " "));
        assertThat(requests.count()).isEqualTo(1);
        assertReserved("50");
    }

    @Test
    @DisplayName("같은 키라도 채팅방이 달라지면 다른 지문으로 거절한다")
    void bindsFingerprintToRoom() {
        admit(KEY, CONTENT);
        Owner other = createOwner("other");
        assertConflict(() -> admission.admit(owner.userId(), other.roomId(), KEY, CONTENT, 1000, 500, Instant.now().plusSeconds(120)));
        assertThat(requests.count()).isEqualTo(1);
        assertReserved("50");
    }

    @Test
    @DisplayName("다른 회원은 같은 키를 사용해도 각자 요청과 예약을 생성한다")
    void scopesKeysToMember() {
        AiRequestReceipt first = admit(KEY, CONTENT);
        Owner other = createOwner("other");
        AiRequestReceipt second = admission.admit(other.userId(), other.roomId(), KEY, CONTENT, 1000, 500, Instant.now().plusSeconds(120));
        assertThat(second.newlyCreated()).isTrue();
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
        assertThat(requests.count()).isEqualTo(2);
        assertReserved("50");
        assertThat(windows.findById(other.windowId()).orElseThrow().getReservedVie()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("동시 동일 접수는 한 요청·한 예약·한 신규 플래그만 남기고 롤백 후 별도 트랜잭션으로 복구한다")
    void concurrentIdenticalRequestsRecoverAfterRollback() throws Exception {
        List<Long> readTransactions = new CopyOnWriteArrayList<>();
        List<Long> writeTransactions = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            readTransactions.add(jdbc.queryForObject("select txid_current()", Long.class));
            return invocation.callRealMethod();
        }).when(requestService).findByKey(eq(owner.userId()), any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            writeTransactions.add(jdbc.queryForObject("select txid_current()", Long.class));
            return invocation.callRealMethod();
        }).when(requestService).create(any());

        List<Outcome> outcomes = raceThroughInsertGate(() -> admit(KEY, CONTENT), () -> admit(KEY, CONTENT));
        assertThat(outcomes).allSatisfy(result -> assertThat(result.error()).isNull());
        AiRequestReceipt left = outcomes.get(0).receipt();
        AiRequestReceipt right = outcomes.get(1).receipt();
        assertThat(left.requestId()).isEqualTo(right.requestId());
        assertThat(outcomes.stream().filter(result -> result.receipt().newlyCreated()).count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertReserved("50");
        assertThat(readTransactions).hasSize(3);
        assertThat(writeTransactions).hasSize(2);
        assertThat(readTransactions.get(2)).isNotIn(writeTransactions);
    }

    @Test
    @DisplayName("동시 같은 키·다른 지문에서는 승자만 신규 접수하고 패자는 충돌로 거절한다")
    void concurrentDifferentContentConflictsWithoutExtraReservation() throws Exception {
        List<Outcome> outcomes = raceThroughInsertGate(() -> admit(KEY, CONTENT), () -> admit(KEY, "다른 사용자 요청"));
        assertThat(outcomes.stream().filter(result -> result.error() == null).count()).isEqualTo(1);
        Outcome rejected = outcomes.stream().filter(result -> result.error() != null).findFirst().orElseThrow();
        assertThat(rejected.error()).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) rejected.error()).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(outcomes.stream().filter(result -> result.receipt() != null && result.receipt().newlyCreated()).count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertReserved("50");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("사전 조회 직후 승자가 잔여량을 소진해도 같은 키를 재조회해 재사용하거나 지문 충돌로 구분한다")
    void recoversWinnerThatExhaustedBalanceAfterInitialLookup(boolean differentContent) {
        jdbc.update("update account_usage_windows set allowance_vie = 50 where id = ?", owner.windowId());
        AtomicBoolean inserted = new AtomicBoolean();
        AtomicReference<UUID> winner = new AtomicReference<>();
        doAnswer(invocation -> {
            Object originalResult = invocation.callRealMethod();
            if (inserted.compareAndSet(false, true)) {
                // [WHY] 사전 조회와 실제 예약 사이에 다른 트랜잭션이 먼저 접수하는 순서를 고정
                winner.set(requiresNew().execute(status -> reservation.reserve(owner.userId(), owner.roomId(), owner.windowId(),
                        KEY, generator.generate(owner.roomId(), CONTENT), 1000, 500, Instant.now().plusSeconds(120))));
            }
            return originalResult;
        }).when(requestService).findByKey(eq(owner.userId()), any());

        if (differentContent) {
            assertConflict(() -> admit(KEY, "다른 사용자 요청"));
        } else {
            AiRequestReceipt replay = admit(KEY, CONTENT);
            assertThat(replay.requestId()).isEqualTo(winner.get());
            assertThat(replay.newlyCreated()).isFalse();
        }
        assertThat(requests.count()).isEqualTo(1);
        assertReserved("50");
    }

    @Test
    @DisplayName("멱등키 이외의 DB 오류는 요청과 예약을 롤백하고 성공으로 바꾸지 않는다")
    void propagatesUnrelatedDatabaseFailure() {
        jdbc.execute("create function request_idempotency_failure() returns trigger language plpgsql as $$ begin raise exception using errcode = '23514', message = '테스트용 접수 실패'; end; $$");
        jdbc.execute("create trigger request_idempotency_failure before insert on ai_requests for each row execute function request_idempotency_failure()");
        assertThatThrownBy(() -> admit(KEY, CONTENT)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.count()).isZero();
        assertReserved("0");
    }

    @Test
    @DisplayName("다른 작업의 버전 변경으로 예약이 충돌하고 같은 키의 승자가 없으면 원래 충돌을 전달한다")
    void propagatesOptimisticConflictWithoutMatchingWinner() {
        doAnswer(invocation -> {
            requiresNew().executeWithoutResult(status -> jdbc.update("update account_usage_windows set version = version + 1 where id = ?", owner.windowId()));
            return invocation.callRealMethod();
        }).when(requestService).create(any());
        assertThatThrownBy(() -> admit(KEY, CONTENT)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(requests.count()).isZero();
        assertReserved("0");
    }

    @Test
    @DisplayName("접수 조율은 호출자 트랜잭션을 중단·복원하고 예약 커밋은 그 롤백과 별개로 유지한다")
    void suspendsCallerTransactionUntilReservationCommit() {
        AiRequestReceipt result = tx().execute(status -> {
            Long original = jdbc.queryForObject("select txid_current()", Long.class);
            AiRequestReceipt receipt = admit(KEY, CONTENT);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select txid_current()", Long.class)).isEqualTo(original);
            status.setRollbackOnly();
            return receipt;
        });
        assertThat(result).isNotNull();
        assertThat(result.newlyCreated()).isTrue();
        assertThat(requests.findById(result.requestId())).isPresent();
        assertReserved("50");
    }

    @Test
    @DisplayName("기한이 지난 처리 중 요청도 같은 키로 재전송하면 실패·재예약하지 않는다")
    void neverRestartsUnknownExpiredRequest() {
        UUID id = admit(KEY, CONTENT).requestId();
        Instant past = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_requests set started_at = ?, processing_deadline_at = ? where id = ?", Timestamp.from(past), Timestamp.from(past.plusSeconds(60)), id);
        AiRequestReceipt replay = admit(KEY, CONTENT);
        assertThat(replay.requestId()).isEqualTo(id);
        assertThat(replay.status()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(replay.newlyCreated()).isFalse();
        assertThat(requests.findById(id).orElseThrow().getFinishedAt()).isNull();
        assertReserved("50");
    }

    private Owner createOwner(String suffix) {
        return tx().execute(status -> {
            User user = User.of(suffix, suffix + "@example.com", AuthTestFixtures.dummyPasswordHash());
            ReflectionTestUtils.setField(user, "signupCompletedAt", Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS));
            users.saveAndFlush(user);
            Reve reve = reves.saveAndFlush(Reve.of("REVE_" + suffix, "루미", Mbti.INFP, "소개", "설정", BigDecimal.ONE));
            UserReve assigned = assignments.saveAndFlush(UserReve.of(user, reve));
            ChatRoom room = rooms.saveAndFlush(ChatRoom.of(assigned));
            AccountUsageWindow window = windows.saveAndFlush(AccountUsageWindow.of(user));
            return new Owner(user.getId(), room.getId(), window.getId());
        });
    }

    private AiRequestReceipt admit(String key, String content) {
        return admission.admit(owner.userId(), owner.roomId(), key, content, 1000, 500, Instant.now().plusSeconds(120));
    }

    private void assertReserved(String expected) {
        AccountUsageWindow window = windows.findById(owner.windowId()).orElseThrow();
        assertThat(window.getReservedVie()).isEqualByComparingTo(expected);
        assertThat(window.getUsedVie()).isZero();
        assertThat(window.getUsedVie().add(window.getReservedVie())).isLessThanOrEqualTo(window.getAllowanceVie());
    }

    private void assertConflict(Runnable operation) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    assertThat(error.getErrorCode().getStatus().value()).isEqualTo(409);
                });
    }

    private List<Outcome> raceThroughInsertGate(Callable<AiRequestReceipt> first, Callable<AiRequestReceipt> second) throws Exception {
        jdbc.execute("create function request_idempotency_gate() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(310038); return new; end; $$");
        jdbc.execute("create trigger request_idempotency_gate before insert on ai_requests for each row execute function request_idempotency_gate()");
        var executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource.getConnection(); Statement gate = connection.createStatement()) {
            gate.execute("select pg_advisory_lock(310038)");
            Future<Outcome> left = null;
            Future<Outcome> right = null;
            try {
                left = executor.submit(() -> attempt(first));
                right = executor.submit(() -> attempt(second));
                awaitBothInsertStatements(left, right);
            } finally {
                gate.execute("select pg_advisory_unlock(310038)");
            }
            assertThat(left).isNotNull();
            assertThat(right).isNotNull();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void awaitBothInsertStatements(Future<Outcome> left, Future<Outcome> right) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_locks where locktype = 'advisory'
                    and classid = 0 and objid = 310038 and not granted
                    and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            if (waiting != null && waiting == 2) {
                return;
            }
            assertThat(left.isDone() || right.isDone()).as("두 접수가 실제 INSERT에 도달해야 함").isFalse();
            Thread.sleep(25);
        }
        throw new AssertionError("두 접수가 PostgreSQL INSERT 게이트에 도달하지 못했습니다.");
    }

    private Outcome attempt(Callable<AiRequestReceipt> operation) {
        try {
            return new Outcome(operation.call(), null);
        } catch (Exception error) {
            return new Outcome(null, error);
        }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private TransactionTemplate requiresNew() {
        TransactionTemplate template = tx();
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
    private record Owner(Long userId, Long roomId, Long windowId) { }
    private record Outcome(AiRequestReceipt receipt, Throwable error) { }
}
