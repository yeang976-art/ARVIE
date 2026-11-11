package com.example.visualchat.domain.auth.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowService;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserReveService;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 가입 API의 저장 결과와 중간 실패 시 트랜잭션 전체 롤백을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:signup-integration;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
class SignUpIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReveRepository reveRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoSpyBean private UserReveService userReveService;
    @MockitoSpyBean private AccountUsageWindowService usageWindowService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareDatabase() {
        // [CAUTION] 이 클래스의 고정 메모리 DB만 정리하며 개발 DB에는 접근하지 않음
        for (String table : new String[]{"user_reves", "account_usage_windows", "users", "reves"}) {
            jdbc.update("delete from " + table);
        }
        reveRepository.saveAndFlush(Reve.of("TRIAL", "체험 레브", "소개", "설정", BigDecimal.ONE));
    }

    @Test
    @DisplayName("가입 성공 응답 뒤 회원·배정·30일 20000 VIE 주기가 함께 커밋되어 있다")
    void commitsAllInitialRecords() throws Exception {
        String response = signup("tester", "user@example.com").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS")).andReturn().getResponse().getContentAsString();
        assertCounts(1);
        String hash = jdbc.queryForObject("select password_hash from users", String.class);
        assertThat(passwordEncoder.matches("Signup1!", hash)).isTrue();
        assertThat(hash).isNotEqualTo("Signup1!");
        assertThat(response).doesNotContain("Signup1!").doesNotContain(hash);
        Instant signupAt = jdbc.queryForObject("select signup_completed_at from users", (rs, row) -> rs.getTimestamp(1).toInstant());
        Instant start = jdbc.queryForObject("select period_start_at from account_usage_windows", (rs, row) -> rs.getTimestamp(1).toInstant());
        Instant end = jdbc.queryForObject("select period_end_at from account_usage_windows", (rs, row) -> rs.getTimestamp(1).toInstant());
        assertThat(start).isEqualTo(signupAt);
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofDays(30));
        assertThat(jdbc.queryForObject("select allowance_vie from account_usage_windows", BigDecimal.class)).isEqualByComparingTo("20000");
        assertThat(jdbc.queryForObject("select used_vie + reserved_vie from account_usage_windows", BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from user_reves r join account_usage_windows w on r.user_id=w.user_id", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 이메일 재가입은 409로 거절하고 추가 배정과 이용 주기를 만들지 않는다")
    void rejectsDuplicateEmail() throws Exception {
        signup("first", "same@example.com").andExpect(status().isCreated());
        signup("second", "same@example.com").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DUPLICATE_EMAIL.getCode()));
        assertCounts(1);
    }

    @Test
    @DisplayName("동일 닉네임 재가입은 이메일이 달라도 409로 거절한다")
    void rejectsDuplicateNickname() throws Exception {
        signup("same", "first@example.com").andExpect(status().isCreated());
        signup("same", "second@example.com").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DUPLICATE_NICKNAME.getCode()));
        assertCounts(1);
    }

    @Test
    @DisplayName("배정할 후보가 없으면 먼저 저장한 회원까지 롤백한다")
    void rollsBackWhenNoTrialCandidateExists() throws Exception {
        jdbc.update("delete from reves");
        signup("tester", "user@example.com").andExpect(status().isServiceUnavailable());
        assertCounts(0);
    }

    @Test
    @DisplayName("레브 배정 저장이 실패하면 회원 저장도 롤백한다")
    void rollsBackWhenAssignmentFails() throws Exception {
        doThrow(new IllegalStateException("테스트용 배정 실패")).when(userReveService).assign(any(User.class), any(Reve.class));
        signup("tester", "user@example.com").andExpect(status().isInternalServerError());
        assertCounts(0);
    }

    @Test
    @DisplayName("최초 이용 주기 생성이 실패하면 회원과 배정을 함께 롤백한다")
    void rollsBackWhenUsageWindowFails() throws Exception {
        doThrow(new IllegalStateException("테스트용 이용 주기 실패")).when(usageWindowService).createInitial(any(User.class));
        signup("tester", "user@example.com").andExpect(status().isInternalServerError());
        assertCounts(0);
    }

    private ResultActions signup(String nickname, String email) throws Exception {
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("nickname", nickname, "email", email, "password", "Signup1!"))));
    }

    private void assertCounts(int expected) {
        for (String table : new String[]{"users", "user_reves", "account_usage_windows"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isEqualTo(expected);
        }
    }
}
