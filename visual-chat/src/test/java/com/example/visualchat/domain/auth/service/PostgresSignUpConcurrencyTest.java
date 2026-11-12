package com.example.visualchat.domain.auth.service;

import com.example.visualchat.domain.reve.enums.Mbti;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** [ROLE] PostgreSQL에서 동시 중복 가입·재시도·DB 저장 실패의 전체 롤백을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=6",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
class PostgresSignUpConcurrencyTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ReveRepository reveRepository;

    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSignUpConcurrencyTest::testUrl);
        registry.add("spring.datasource.username", () -> testSetting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> testSetting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 정리·스키마 재생성을 수행하므로 지정된 로컬 테스트 DB 이름만 허용
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
        jdbc.execute("drop trigger if exists signup_test_gate on users");
        jdbc.execute("drop function if exists signup_test_gate()");
        jdbc.execute("drop trigger if exists signup_test_window_failure on account_usage_windows");
        jdbc.execute("drop function if exists signup_test_window_failure()");
        jdbc.execute("truncate table messages, ai_requests, chat_rooms, user_reves, account_usage_windows, users, reves restart identity cascade");
        reveRepository.saveAndFlush(Reve.of("TRIAL", "체험 레브", Mbti.ENFP, "소개", "설정", BigDecimal.ONE));
    }

    @Test
    @DisplayName("두 요청이 이메일 사전 검사를 모두 통과해도 DB가 한 건만 허용하고 나머지는 이메일 중복 409를 반환한다")
    void rejectsConcurrentDuplicateEmail(CapturedOutput output) throws Exception {
        assertConcurrentSignups("first", "same@example.com", "second", "same@example.com", ErrorCode.DUPLICATE_EMAIL);
        // [CAUTION] DB 유일성 위반의 상세 메시지가 애플리케이션 로그에 이메일을 노출하지 않아야 함
        assertThat(output.getAll()).doesNotContain("same@example.com");
    }

    @Test
    @DisplayName("두 요청이 닉네임 사전 검사를 모두 통과해도 DB가 한 건만 허용하고 나머지는 닉네임 중복 409를 반환한다")
    void rejectsConcurrentDuplicateNickname() throws Exception {
        assertConcurrentSignups("same", "first@example.com", "same", "second@example.com", ErrorCode.DUPLICATE_NICKNAME);
    }

    @Test
    @DisplayName("같은 가입 요청을 재시도해도 회원·배정·초기 주기가 추가로 생성되지 않는다")
    void rejectsRepeatedSignupWithoutAdditionalRecords() throws Exception {
        assertThat(signup("tester", "user@example.com").status()).isEqualTo(201);
        SignupResult retry = signup("tester", "user@example.com");
        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.code()).isEqualTo(ErrorCode.DUPLICATE_EMAIL.getCode());
        assertCounts(1);
    }

    @Test
    @DisplayName("PostgreSQL에서 최초 이용 주기 INSERT가 실패하면 회원과 배정도 모두 롤백한다")
    void rollsBackAllRecordsWhenDatabaseRejectsUsageWindow() throws Exception {
        // [WHY] 마지막 저장 단계의 실제 DB 실패를 주입해 앞선 INSERT까지 롤백되는지 검증
        jdbc.execute("""
                create function signup_test_window_failure() returns trigger language plpgsql as $$
                begin
                    raise exception using errcode = '23514', message = '테스트용 이용 주기 저장 실패';
                end;
                $$
                """);
        jdbc.execute("create trigger signup_test_window_failure before insert on account_usage_windows for each row execute function signup_test_window_failure()");
        SignupResult result = signup("tester", "user@example.com");
        assertThat(result.status()).isEqualTo(500);
        assertThat(result.code()).isEqualTo(ErrorCode.SERVER_ERROR.getCode());
        assertCounts(0);
        assertThat(jdbc.queryForObject("select count(*) from reves", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("도메인 표기가 다른 동시 가입도 한 건만 허용하고 오류 로그에 이메일을 남기지 않는다")
    void rejectsConcurrentDuplicateWithDifferentDomainCase(CapturedOutput output) throws Exception {
        assertConcurrentSignups("first", "User@EXAMPLE.COM", "second", "User@example.com", ErrorCode.DUPLICATE_EMAIL);

        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("User@example.com");
        assertThat(output.getAll()).doesNotContain("User@EXAMPLE.COM", "User@example.com");
    }
    private void assertConcurrentSignups(String firstNickname, String firstEmail,
                                         String secondNickname, String secondEmail, ErrorCode expected) throws Exception {
        jdbc.execute("""
                create function signup_test_gate() returns trigger language plpgsql as $$
                begin
                    perform pg_advisory_xact_lock(310031);
                    return new;
                end;
                $$
                """);
        jdbc.execute("create trigger signup_test_gate before insert on users for each row execute function signup_test_gate()");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection control = dataSource.getConnection(); Statement gate = control.createStatement()) {
            gate.execute("select pg_advisory_lock(310031)");
            Future<SignupResult> first = null;
            Future<SignupResult> second = null;
            try {
                first = executor.submit(() -> signup(firstNickname, firstEmail));
                second = executor.submit(() -> signup(secondNickname, secondEmail));
                awaitBothInsertsWaiting(first, second);
            } finally {
                gate.execute("select pg_advisory_unlock(310031)");
            }
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            List<SignupResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(SignupResult::status).containsExactlyInAnyOrder(201, 409);
            SignupResult rejected = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(rejected.code()).isEqualTo(expected.getCode());
            assertCounts(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void awaitBothInsertsWaiting(Future<SignupResult> first, Future<SignupResult> second) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            // [WHY] 두 요청이 실제 사전 조회를 마치고 INSERT 직전까지 도달한 것을 DB 대기 상태로 확인
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_locks where locktype = 'advisory'
                    and classid = 0 and objid = 310031 and not granted
                    and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            if (waiting != null && waiting == 2) {
                return;
            }
            assertThat(first.isDone() || second.isDone()).as("두 요청 모두 INSERT 게이트에 도달해야 함").isFalse();
            Thread.sleep(25);
        }
        throw new AssertionError("두 가입 요청이 제한 시간 안에 INSERT 게이트에 도달하지 못했습니다.");
    }

    private SignupResult signup(String nickname, String email) throws Exception {
        var response = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", nickname, "email", email, "password", "Signup1!"))))
                .andReturn().getResponse();
        String code = objectMapper.readTree(response.getContentAsString()).get("code").asText();
        return new SignupResult(response.getStatus(), code);
    }

    private void assertCounts(int expected) {
        for (String table : new String[]{"users", "user_reves", "account_usage_windows"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isEqualTo(expected);
        }
    }

    private record SignupResult(int status, String code) { }
}
