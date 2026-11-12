package com.example.visualchat.domain.chatroom.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.chatroom.dto.ChatRoomResponse;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** [ROLE] PostgreSQL에서 동시 방 생성의 단일 저장·롤백 후 재조회·트랜잭션 경계를 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=6",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
class PostgresChatRoomConcurrencyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository users;
    @Autowired private ReveRepository reves;
    @Autowired private UserReveRepository assignments;
    @Autowired private TokenIssuer tokens;
    @Autowired private ChatRoomCommand command;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ChatRoomService roomService;
    private Long userId;
    private Long reveId;
    private Long assignmentId;

    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresChatRoomConcurrencyTest::testUrl);
        registry.add("spring.datasource.username", () -> testSetting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> testSetting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 스키마와 데이터를 정리하므로 지정된 로컬 테스트 DB만 허용
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
        jdbc.execute("drop trigger if exists room_test_gate on chat_rooms");
        jdbc.execute("drop function if exists room_test_gate()");
        jdbc.execute("drop trigger if exists room_test_failure on chat_rooms");
        jdbc.execute("drop function if exists room_test_failure()");
        jdbc.execute("drop trigger if exists signup_test_gate on users");
        jdbc.execute("drop function if exists signup_test_gate()");
        jdbc.execute("drop trigger if exists signup_test_window_failure on account_usage_windows");
        jdbc.execute("drop function if exists signup_test_window_failure()");
        jdbc.execute("truncate table messages, ai_requests, chat_rooms, user_reves, account_usage_windows, users, reves restart identity cascade");
        User owner = users.saveAndFlush(User.of("owner", "owner@example.com", AuthTestFixtures.dummyPasswordHash()));
        Reve reve = reves.saveAndFlush(Reve.of("OWN", "루미", Mbti.INFP, "소개", "서버 설정", BigDecimal.ONE));
        UserReve assignment = assignments.saveAndFlush(UserReve.of(owner, reve));
        userId = owner.getId();
        reveId = reve.getId();
        assignmentId = assignment.getId();
    }

    @Test
    @DisplayName("동시 최초 요청은 두 생성 시도 중 한 건만 저장하고 롤백한 요청도 같은 방을 반환한다")
    void returnsSameCommittedRoomAfterConcurrentCreation() throws Exception {
        jdbc.execute("""
                create function room_test_gate() returns trigger language plpgsql as $$
                begin
                    perform pg_advisory_xact_lock(310034);
                    return new;
                end;
                $$
                """);
        jdbc.execute("create trigger room_test_gate before insert on chat_rooms for each row execute function room_test_gate()");
        var executor = Executors.newFixedThreadPool(2);
        try (Connection control = dataSource.getConnection(); Statement gate = control.createStatement()) {
            gate.execute("select pg_advisory_lock(310034)");
            Future<OpenResult> first = null;
            Future<OpenResult> second = null;
            try {
                first = executor.submit(this::open);
                second = executor.submit(this::open);
                awaitBothInsertsWaiting(first, second);
            } finally {
                gate.execute("select pg_advisory_unlock(310034)");
            }
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            List<OpenResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(OpenResult::status).containsOnly(200);
            assertThat(results).extracting(OpenResult::code).containsOnly("SUCCESS");
            assertThat(results.get(0).roomId()).isNotNull().isEqualTo(results.get(1).roomId());
            assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select retention_days from chat_rooms", Short.class)).isEqualTo((short) 90);
            // [WHY] 실제 DB 충돌 뒤 추가 조회가 실행되어 복구됐는지 호출 경로도 확인
            verify(roomService, times(2)).create(any(UserReve.class));
            verify(roomService, times(3)).findByAssignment(userId, assignmentId);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("호출자의 트랜잭션은 중단·복원되고 채팅방 생성 커밋은 호출자 롤백에 휩쓸리지 않는다")
    void suspendsCallerTransactionAroundOpen() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        ChatRoomResponse response = outer.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            Long before = jdbc.queryForObject("select txid_current()", Long.class);
            ChatRoomResponse opened = command.open(userId, reveId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select txid_current()", Long.class)).isEqualTo(before);
            status.setRollbackOnly();
            return opened;
        });

        assertThat(response).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms where id = ?", Integer.class, response.roomId())).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 DB 제약 오류는 복구하지 않고 롤백하며 이후 정상 요청은 다시 생성할 수 있다")
    void rollsBackUnrelatedDatabaseFailure() throws Exception {
        jdbc.execute("""
                create function room_test_failure() returns trigger language plpgsql as $$
                begin
                    raise exception using errcode = '23514', message = '테스트용 채팅방 저장 실패';
                end;
                $$
                """);
        jdbc.execute("create trigger room_test_failure before insert on chat_rooms for each row execute function room_test_failure()");
        OpenResult failed = open();
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.code()).isEqualTo(ErrorCode.SERVER_ERROR.getCode());
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Integer.class)).isZero();
        assertThat(assignments.count()).isEqualTo(1);
        verify(roomService, times(1)).findByAssignment(userId, assignmentId);

        jdbc.execute("drop trigger room_test_failure on chat_rooms");
        jdbc.execute("drop function room_test_failure()");
        assertThat(open().status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Integer.class)).isEqualTo(1);
    }

    private void awaitBothInsertsWaiting(Future<OpenResult> first, Future<OpenResult> second) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            // [WHY] 두 요청이 기존 방 조회를 마치고 실제 INSERT에 도달한 뒤에만 게이트를 해제
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_locks where locktype = 'advisory'
                    and classid = 0 and objid = 310034 and not granted
                    and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            if (waiting != null && waiting == 2) {
                return;
            }
            assertThat(first.isDone() || second.isDone()).as("두 요청 모두 INSERT 게이트에 도달해야 함").isFalse();
            Thread.sleep(25);
        }
        throw new AssertionError("두 채팅방 생성 요청이 INSERT 게이트에 도달하지 못했습니다.");
    }

    private OpenResult open() throws Exception {
        var response = mockMvc.perform(post("/api/reves/{id}/chat-rooms", reveId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(userId).token()))
                .andReturn().getResponse();
        Map<?, ?> body = mapper.readValue(response.getContentAsString(), Map.class);
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        Long roomId = data == null ? null : ((Number) data.get("roomId")).longValue();
        return new OpenResult(response.getStatus(), (String) body.get("code"), roomId);
    }

    private record OpenResult(int status, String code, Long roomId) { }
}
