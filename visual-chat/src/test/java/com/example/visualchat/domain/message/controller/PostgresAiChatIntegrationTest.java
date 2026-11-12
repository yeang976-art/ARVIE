package com.example.visualchat.domain.message.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** [ROLE] 실제 PostgreSQL에서 API 계약·동시 멱등 전송·답변 INSERT 실패의 원자성을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=6", "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET, "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@Import(AiChatIntegrationContract.ClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIf(value = "hasPostgresTestDatabase", disabledReason = "전용 PostgreSQL 테스트 DB 설정 시 실행")
class PostgresAiChatIntegrationTest extends AiChatIntegrationContract {
    static boolean hasPostgresTestDatabase() {
        return System.getenv("ARVIE_POSTGRES_TEST_URL") != null
                || ("true".equalsIgnoreCase(System.getenv("CI")) && "arvie_test".equals(System.getenv("POSTGRES_DB")));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresAiChatIntegrationTest::testUrl);
        registry.add("spring.datasource.username", () -> setting("ARVIE_POSTGRES_TEST_USER", "POSTGRES_USER"));
        registry.add("spring.datasource.password", () -> setting("ARVIE_POSTGRES_TEST_PASSWORD", "POSTGRES_PASSWORD"));
    }

    private static String testUrl() {
        String explicit = System.getenv("ARVIE_POSTGRES_TEST_URL");
        String url = explicit != null ? explicit : "jdbc:postgresql://127.0.0.1:5432/arvie_test";
        // [CAUTION] 개발·운영 DB에서 정리와 스키마 생성이 실행되지 않도록 제한
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/(arvie_signup_test|arvie_test)")) {
            throw new IllegalStateException("전용 로컬 PostgreSQL 테스트 DB URL만 사용할 수 있습니다.");
        }
        return url;
    }

    private static String setting(String local, String ci) {
        String value = System.getenv(local) != null ? System.getenv(local) : System.getenv(ci);
        if (value == null || value.isBlank()) throw new IllegalStateException("PostgreSQL 테스트 연결 설정이 필요합니다.");
        return value;
    }

    @Test
    @DisplayName("동일 키 최초 전송이 경합해도 AI 생성·예약·사용자 메시지는 한 번만 발생한다")
    void concurrentHttpAdmissionGeneratesOnce() throws Exception {
        CyclicBarrier countedBoth = new CyclicBarrier(2);
        doAnswer(invocation -> {
            countedBoth.await(10, TimeUnit.SECONDS);
            return 1000L;
        }).when(provider).countInputTokens(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> send(owner, "concurrent-key", CONTENT));
            var second = pool.submit(() -> send(owner, "concurrent-key", CONTENT));
            var left = first.get(20, TimeUnit.SECONDS);
            var right = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(200, 202)).contains(left.getStatus(), right.getStatus());
            assertThat(data(left, left.getStatus()).get("requestId")).isEqualTo(data(right, right.getStatus()).get("requestId"));
        }
        verify(provider, times(2)).countInputTokens(any());
        verify(provider, times(1)).generate(any());
        assertThat(requests.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(2);
        assertBalances("20", "0");
    }

    @Test
    @DisplayName("PostgreSQL 답변 INSERT 트리거 오류도 제공사 ID·성공 상태·차감 전체를 롤백한다")
    void actualInsertFailureRollsBackCompletion() throws Exception {
        jdbc.execute("""
                create or replace function arvie_test_reject_answer_39() returns trigger language plpgsql as $$
                begin
                  if NEW.sender = 'ASSISTANT' then
                    raise exception 'test_answer_insert_failure' using errcode = '23514';
                  end if;
                  return NEW;
                end; $$
                """);
        jdbc.execute("create trigger arvie_test_reject_answer_39 before insert on messages for each row execute function arvie_test_reject_answer_39()");
        try {
            error(send(owner, "trigger-failure", CONTENT), 500, ErrorCode.SERVER_ERROR);
            assertPendingRollback();
        } finally {
            jdbc.execute("drop trigger if exists arvie_test_reject_answer_39 on messages");
            jdbc.execute("drop function if exists arvie_test_reject_answer_39()");
        }
    }
}
