package com.example.visualchat.domain.account_usage_window.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 고정 시각과 실제 JWT로 계정 VIE 조회·주기 갱신·이전 예약 보존을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:usage-issue36;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@Import(UsageIntegrationTest.FixedTime.class)
class UsageIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00.123456Z");
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private AccountUsageWindowRepository windows;
    @Autowired private JwtEncoder encoder;
    private User owner;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock usageTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareMember() {
        // [CAUTION] 이 클래스의 전용 메모리 DB만 정리하며 개발 DB에는 접근하지 않음
        windows.deleteAll();
        users.deleteAll();
        owner = member("owner", NOW.minus(Duration.ofDays(10)));
    }

    @Test
    @DisplayName("기존 주기의 제공량·사용량·예약량·잔여량과 종료 시각을 그대로 반환한다")
    void returnsExistingUsageWithoutReset() throws Exception {
        AccountUsageWindow initial = initial(owner, "250.125");
        jdbc.update("update account_usage_windows set used_vie = ? where id = ?", new BigDecimal("125.375"), initial.getId());

        Map<?, ?> data = usage();

        assertThat(id(data)).isEqualTo(initial.getId());
        assertThat(data.size()).isEqualTo(7);
        assertVie(data, "allowanceVie", "20000");
        assertVie(data, "usedVie", "125.375");
        assertVie(data, "reservedVie", "250.125");
        assertVie(data, "remainingVie", "19624.5");
        assertPeriod(data, owner.getSignupCompletedAt());
        assertThat(windows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 주기 행이 없으면 20000 VIE와 사용·예약량 0으로 한 번 생성한다")
    void createsMissingCurrentWindow() throws Exception {
        Map<?, ?> data = usage();
        assertVie(data, "allowanceVie", "20000");
        assertVie(data, "usedVie", "0");
        assertVie(data, "reservedVie", "0");
        assertVie(data, "remainingVie", "20000");
        assertPeriod(data, owner.getSignupCompletedAt());
        assertThat(windows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("정확한 30일 경계에서는 새 주기를 만들고 이전 사용량·예약량은 보존한다")
    void rollsOverExactlyAtBoundary() throws Exception {
        owner = changeSignup(NOW.minus(Duration.ofDays(30)));
        AccountUsageWindow previous = initial(owner, "500.125");
        jdbc.update("update account_usage_windows set used_vie = ? where id = ?", new BigDecimal("100"), previous.getId());

        Map<?, ?> data = usage();

        assertThat(id(data)).isNotEqualTo(previous.getId());
        assertPeriod(data, NOW);
        assertVie(data, "usedVie", "0");
        assertVie(data, "reservedVie", "0");
        assertVie(data, "remainingVie", "20000");
        AccountUsageWindow preserved = windows.findById(previous.getId()).orElseThrow();
        assertThat(preserved.getUsedVie()).isEqualByComparingTo("100");
        assertThat(preserved.getReservedVie()).isEqualByComparingTo("500.125");
        assertThat(windows.count()).isEqualTo(2);
        assertThat(users.findById(owner.getId()).orElseThrow().getSignupCompletedAt()).isEqualTo(owner.getSignupCompletedAt());
    }

    @Test
    @DisplayName("경계 직전 1마이크로초에는 기존 주기를 유지한다")
    void doesNotRollOverBeforeStoredTimeBoundary() throws Exception {
        // [NOTE] DB 저장 정밀도에서는 마이크로초, 계산 모델에서는 나노초 경계를 각각 검증
        owner = changeSignup(NOW.minus(Duration.ofDays(30)).plusNanos(1000));
        AccountUsageWindow initial = initial(owner, "10");
        Map<?, ?> data = usage();
        assertThat(id(data)).isEqualTo(initial.getId());
        assertPeriod(data, owner.getSignupCompletedAt());
        assertVie(data, "reservedVie", "10");
        assertThat(windows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("95일 미접속 후에는 가입 후 90일부터 120일까지의 현재 주기만 추가한다")
    void createsOnlyCurrentWindowAfterLongAbsence() throws Exception {
        owner = changeSignup(NOW.minus(Duration.ofDays(95)));
        AccountUsageWindow previous = initial(owner, "333.333333");
        Map<?, ?> data = usage();

        assertPeriod(data, owner.getSignupCompletedAt().plus(Duration.ofDays(90)));
        assertThat(windows.count()).isEqualTo(2);
        assertThat(windows.findById(previous.getId()).orElseThrow().getReservedVie()).isEqualByComparingTo("333.333333");
    }

    @Test
    @DisplayName("재접속과 여러 기기 요청에도 같은 계정 주기와 사용량을 유지한다")
    void keepsSameWindowAcrossRequests() throws Exception {
        AccountUsageWindow initial = initial(owner, "123.5");
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/users/me/usage").session(new MockHttpSession())
                            .header(HttpHeaders.USER_AGENT, "test-device-" + request)
                            .header(HttpHeaders.AUTHORIZATION, bearer(owner.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.usageWindowId").value(initial.getId()))
                    .andExpect(jsonPath("$.data.reservedVie").value(123.5));
        }
        assertThat(windows.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인 ID를 쿼리에 넣어도 토큰 소유자의 주기와 수량만 반환한다")
    void ignoresAnotherMembersIdentifier() throws Exception {
        AccountUsageWindow ownWindow = initial(owner, "123");
        User other = member("other", NOW.minus(Duration.ofDays(20)));
        initial(other, "456");

        mockMvc.perform(get("/api/users/me/usage").param("userId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usageWindowId").value(ownWindow.getId()))
                .andExpect(jsonPath("$.data.reservedVie").value(123));
        assertThat(windows.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("미인증 요청에는 주기를 생성하지 않고 401을 반환한다")
    void rejectsAnonymousRequest() throws Exception {
        assertError(mockMvc.perform(get("/api/users/me/usage")), ErrorCode.AUTHENTICATION_REQUIRED);
        assertThat(windows.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-member-id", "0"})
    @DisplayName("회원 식별값이 잘못된 토큰으로 이용량을 조회할 수 없다")
    void rejectsInvalidSubject(String subject) throws Exception {
        String token = JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(subject).build());
        assertError(mockMvc.perform(get("/api/users/me/usage").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), ErrorCode.INVALID_TOKEN);
        assertThat(windows.count()).isZero();
    }

    @Test
    @DisplayName("토큰이 가리키는 회원이 삭제되면 주기를 생성하지 않고 404를 반환한다")
    void rejectsDeletedMember() throws Exception {
        String bearer = bearer(owner.getId());
        users.deleteById(owner.getId());
        assertError(mockMvc.perform(get("/api/users/me/usage").header(HttpHeaders.AUTHORIZATION, bearer)), ErrorCode.USER_NOT_FOUND);
        assertThat(windows.count()).isZero();
    }

    @Test
    @DisplayName("서버의 현재 시각이 가입 시각보다 이전이면 내부 오류로 처리한다")
    void rejectsCurrentTimeBeforeSignup() throws Exception {
        owner = changeSignup(NOW.plusSeconds(1));
        assertError(mockMvc.perform(get("/api/users/me/usage").header(HttpHeaders.AUTHORIZATION, bearer(owner.getId()))), ErrorCode.SERVER_ERROR);
        assertThat(windows.count()).isZero();
    }

    private User member(String nickname, Instant signup) {
        User user = User.of(nickname, nickname + "@example.com", AuthTestFixtures.dummyPasswordHash());
        ReflectionTestUtils.setField(user, "signupCompletedAt", signup);
        return users.saveAndFlush(user);
    }

    private User changeSignup(Instant signup) {
        ReflectionTestUtils.setField(owner, "signupCompletedAt", signup);
        return users.saveAndFlush(owner);
    }

    private AccountUsageWindow initial(User user, String reserved) {
        AccountUsageWindow window = AccountUsageWindow.of(user);
        window.reserveVie(new BigDecimal(reserved));
        return windows.saveAndFlush(window);
    }

    private String bearer(Long id) {
        // [WHY] 주기 계산용 고정 Clock과 JWT 유효성 검증의 실제 시계를 분리
        return "Bearer " + JwtTestFixtures.sign(encoder, JwtTestFixtures.claims().subject(id.toString()).build());
    }

    private Map<?, ?> usage() throws Exception {
        String json = mockMvc.perform(get("/api/users/me/usage").header(HttpHeaders.AUTHORIZATION, bearer(owner.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> body = mapper.readValue(json, Map.class);
        return (Map<?, ?>) body.get("data");
    }

    private long id(Map<?, ?> data) {
        return ((Number) data.get("usageWindowId")).longValue();
    }

    private void assertVie(Map<?, ?> data, String field, String value) {
        assertThat(new BigDecimal(data.get(field).toString())).isEqualByComparingTo(value);
    }

    private void assertPeriod(Map<?, ?> data, Instant start) {
        assertThat(Instant.parse((String) data.get("periodStartAt"))).isEqualTo(start);
        assertThat(Instant.parse((String) data.get("periodEndAt"))).isEqualTo(start.plus(Duration.ofDays(30)));
    }

    private void assertError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
