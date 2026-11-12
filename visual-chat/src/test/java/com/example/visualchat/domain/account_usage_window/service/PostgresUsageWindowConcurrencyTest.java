package com.example.visualchat.domain.account_usage_window.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** [ROLE] PostgreSQL에서 경계 시점의 동시 주기 생성과 롤백 후 재조회·이전 예약 보존을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=6",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@Import(PostgresUsageWindowConcurrencyTest.FixedTime.class)
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
class PostgresUsageWindowConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00.123456Z");
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository users;
    @Autowired private AccountUsageWindowRepository windows;
    @Autowired private JwtEncoder encoder;
    @Autowired private AccountUsageWindowCommand command;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AccountUsageWindowService windowService;
    private Long userId;
    private Long previousId;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock usageTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresUsageWindowConcurrencyTest::testUrl);
        registry.add("spring.datasource.username", () -> testSetting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> testSetting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 스키마 재생성과 데이터 정리를 수행하므로 지정된 로컬 테스트 DB만 허용
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
        jdbc.execute("drop trigger if exists usage_test_gate on account_usage_windows");
        jdbc.execute("drop function if exists usage_test_gate()");
        jdbc.execute("drop trigger if exists usage_test_failure on account_usage_windows");
        jdbc.execute("drop function if exists usage_test_failure()");
        jdbc.execute("drop trigger if exists signup_test_gate on users");
        jdbc.execute("drop function if exists signup_test_gate()");
        jdbc.execute("drop trigger if exists signup_test_window_failure on account_usage_windows");
        jdbc.execute("drop function if exists signup_test_window_failure()");
        jdbc.execute("drop trigger if exists room_test_gate on chat_rooms");
        jdbc.execute("drop function if exists room_test_gate()");
        jdbc.execute("drop trigger if exists room_test_failure on chat_rooms");
        jdbc.execute("drop function if exists room_test_failure()");
        jdbc.execute("truncate table messages, ai_requests, chat_rooms, user_reves, account_usage_windows, users, reves restart identity cascade");
        User owner = User.of("owner", "owner@example.com", AuthTestFixtures.dummyPasswordHash());
        ReflectionTestUtils.setField(owner, "signupCompletedAt", NOW.minus(Duration.ofDays(30)));
        users.saveAndFlush(owner);
        AccountUsageWindow previous = AccountUsageWindow.of(owner);
        previous.reserveVie(new BigDecimal("250.125"));
        windows.saveAndFlush(previous);
        userId = owner.getId();
        previousId = previous.getId();
    }

    @Test
    @DisplayName("경계의 동시 조회는 새 주기 한 건만 만들고 두 요청에 같은 주기를 반환한다")
    void createsCurrentWindowOnceAndRecoversDuplicate() throws Exception {
        jdbc.execute("""
                create function usage_test_gate() returns trigger language plpgsql as $$
                begin
                    perform pg_advisory_xact_lock(310036);
                    return new;
                end;
                $$
                """);
        jdbc.execute("create trigger usage_test_gate before insert on account_usage_windows for each row execute function usage_test_gate()");
        var executor = Executors.newFixedThreadPool(2);
        try (Connection control = dataSource.getConnection(); Statement gate = control.createStatement()) {
            gate.execute("select pg_advisory_lock(310036)");
            Future<UsageResult> first = null;
            Future<UsageResult> second = null;
            try {
                first = executor.submit(this::usage);
                second = executor.submit(this::usage);
                awaitBothInsertsWaiting(first, second);
            } finally {
                gate.execute("select pg_advisory_unlock(310036)");
            }
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            List<UsageResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(UsageResult::status).containsOnly(200);
            assertThat(results).extracting(UsageResult::code).containsOnly("SUCCESS");
            assertThat(results.get(0).windowId()).isNotNull().isEqualTo(results.get(1).windowId());
            assertThat(results.get(0).windowId()).isNotEqualTo(previousId);
            assertThat(jdbc.queryForObject("select count(*) from account_usage_windows", Integer.class)).isEqualTo(2);
            assertThat(windows.findById(results.get(0).windowId()).orElseThrow().getAllowanceVie()).isEqualByComparingTo("20000");
            assertPreviousReservation();
            verify(windowService, times(2)).create(any(), any());
            verify(windowService, times(3)).findByPeriod(userId, NOW);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("호출자의 트랜잭션을 중단·복원하고 그 롤백과 별개로 새 주기를 커밋한다")
    void suspendsCallerTransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        UsageResponse response = outer.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Long before = jdbc.queryForObject("select txid_current()", Long.class);
            UsageResponse current = command.getCurrentUsage(userId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select txid_current()", Long.class)).isEqualTo(before);
            status.setRollbackOnly();
            return current;
        });

        assertThat(response).isNotNull();
        assertThat(windows.findById(response.usageWindowId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from account_usage_windows", Integer.class)).isEqualTo(2);
        assertPreviousReservation();
    }

    @Test
    @DisplayName("다른 DB 제약 오류는 새 주기를 롤백하며 이전 주기는 보존한다")
    void rollsBackUnrelatedDatabaseFailure() throws Exception {
        jdbc.execute("""
                create function usage_test_failure() returns trigger language plpgsql as $$
                begin
                    raise exception using errcode = '23514', message = '테스트용 주기 저장 실패';
                end;
                $$
                """);
        jdbc.execute("create trigger usage_test_failure before insert on account_usage_windows for each row execute function usage_test_failure()");
        UsageResult failed = usage();
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo(ErrorCode.SERVER_ERROR.getCode());
        assertThat(jdbc.queryForObject("select count(*) from account_usage_windows", Integer.class)).isEqualTo(1);
        assertPreviousReservation();
        verify(windowService, times(1)).findByPeriod(userId, NOW);

        jdbc.execute("drop trigger usage_test_failure on account_usage_windows");
        jdbc.execute("drop function usage_test_failure()");
        assertThat(usage().status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from account_usage_windows", Integer.class)).isEqualTo(2);
    }

    private void assertPreviousReservation() {
        assertThat(windows.findById(previousId).orElseThrow().getReservedVie()).isEqualByComparingTo("250.125");
    }

    private void awaitBothInsertsWaiting(Future<UsageResult> first, Future<UsageResult> second) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_locks where locktype = 'advisory'
                    and classid = 0 and objid = 310036 and not granted
                    and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            // [WHY] 두 요청이 실제 조회를 통과해 INSERT에 도달한 것을 확인한 뒤 진행
            if (waiting != null && waiting == 2) {
                return;
            }
            assertThat(first.isDone() || second.isDone()).as("두 요청 모두 INSERT 게이트에 도달해야 함").isFalse();
            Thread.sleep(25);
        }
        throw new AssertionError("두 이용 주기 생성 요청이 INSERT 게이트에 도달하지 못했습니다.");
    }

    private UsageResult usage() throws Exception {
        String token = JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(userId.toString()).build());
        var response = mockMvc.perform(get("/api/users/me/usage").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse();
        Map<?, ?> body = mapper.readValue(response.getContentAsString(), Map.class);
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        Long id = data == null ? null : ((Number) data.get("usageWindowId")).longValue();
        return new UsageResult(response.getStatus(), (String) body.get("code"), id);
    }

    private record UsageResult(int status, String code, Long windowId) { }
}
