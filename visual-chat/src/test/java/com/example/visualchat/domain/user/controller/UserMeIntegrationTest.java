package com.example.visualchat.domain.user.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.port.TokenIssuer;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 JWT 인증·회원 조회·공통 응답을 연결해 본인 정보 접근 경계를 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-me-issue32;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
class UserMeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenIssuer tokenIssuer;
    @Autowired private JwtEncoder jwtEncoder;

    private User member;
    private User otherMember;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @BeforeEach
    void prepareMembers() {
        // [CAUTION] 이 클래스 전용 메모리 DB의 회원만 정리하며 개발 DB에는 접근하지 않음
        userRepository.deleteAll();
        User saved = userRepository.saveAndFlush(User.of("tester", "User@example.com", AuthTestFixtures.dummyPasswordHash()));
        otherMember = userRepository.saveAndFlush(User.of("another", "another@example.com", AuthTestFixtures.dummyPasswordHash()));
        // [NOTE] DB에 저장된 시간 정밀도를 기준으로 응답을 비교
        member = userRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    @DisplayName("발급된 토큰으로 본인 정보를 조회하면 허용된 네 필드만 반환한다")
    void returnsOnlyOwnPublicAccountFields() throws Exception {
        var response = request(tokenIssuer.issue(member.getId()).token())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(member.getId()))
                .andExpect(jsonPath("$.data.nickname").value(member.getNickname()))
                .andExpect(jsonPath("$.data.email").value(member.getEmail()))
                .andReturn().getResponse();

        Map<?, ?> body = objectMapper.readValue(response.getContentAsString(), Map.class);
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data.size()).isEqualTo(4);
        assertThat(data.containsKey("signupCompletedAt")).isTrue();
        assertThat(Instant.parse((String) data.get("signupCompletedAt"))).isEqualTo(member.getSignupCompletedAt());
        assertThat(response.getContentAsString()).doesNotContain("password", member.getPasswordHash());
    }

    @Test
    @DisplayName("다른 회원 ID를 쿼리에 넣어도 토큰이 가리키는 본인 정보만 조회한다")
    void ignoresClientSuppliedMemberId() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .param("userId", otherMember.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(member.getId()).token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(member.getId()))
                .andExpect(jsonPath("$.data.email").value(member.getEmail()));
    }

    @Test
    @DisplayName("토큰 없이 회원 ID만 전달하면 인증 필요 공통 응답을 반환한다")
    void rejectsUnauthenticatedRequest() throws Exception {
        assertError(mockMvc.perform(get("/api/users/me").param("userId", member.getId().toString())),
                ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @ParameterizedTest(name = "잘못된 회원 식별값 사례 {index}")
    @ValueSource(strings = {"not-a-number", "0", "-1", "1.5", "9223372036854775808"})
    @DisplayName("정상 서명된 토큰도 회원 식별값이 양수 Long이 아니면 401로 거절한다")
    void rejectsInvalidMemberIdentifier(String subject) throws Exception {
        // [WHY] 인증을 모킹하지 않고 실제 검증기를 통과한 sub의 숫자 변환 경계를 검증
        String token = JwtTestFixtures.sign(jwtEncoder, JwtTestFixtures.claims().subject(subject).build());

        assertError(request(token), ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("Long 최댓값 식별값은 변환 오류 대신 회원이 없다는 404로 처리한다")
    void acceptsUpperNumericBoundaryButRejectsMissingMember() throws Exception {
        assertError(request(tokenIssuer.issue(Long.MAX_VALUE).token()), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("존재하지 않는 회원을 가리키는 유효한 토큰은 404 공통 응답을 반환한다")
    void rejectsMissingMember() throws Exception {
        long missingId = Math.max(member.getId(), otherMember.getId()) + 1000;

        assertError(request(tokenIssuer.issue(missingId).token()), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("토큰 발급 후 회원이 삭제되면 만료 전이라도 회원 정보를 반환하지 않는다")
    void rejectsDeletedMemberWithUnexpiredToken() throws Exception {
        String token = tokenIssuer.issue(member.getId()).token();
        userRepository.deleteById(member.getId());

        assertError(request(token), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("시계 오차 허용 범위를 지나 만료된 토큰은 회원 조회에 사용할 수 없다")
    void rejectsExpiredToken() throws Exception {
        Instant now = Instant.now();
        String token = JwtTestFixtures.sign(jwtEncoder, JwtTestFixtures.claims()
                .subject(member.getId().toString())
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(600)).build());

        assertError(request(token), ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("실제 회원 ID라도 다른 키로 위조한 토큰으로는 정보를 조회할 수 없다")
    void rejectsForgedToken() throws Exception {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build();
        String token = JwtTestFixtures.sign(otherEncoder,
                JwtTestFixtures.claims().subject(otherMember.getId().toString()).build());

        assertError(request(token), ErrorCode.INVALID_TOKEN);
    }

    private ResultActions request(String token) throws Exception {
        return mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void assertError(ResultActions result, ErrorCode errorCode) throws Exception {
        result.andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
