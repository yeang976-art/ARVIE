package com.example.visualchat.common.config;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.global.config.SecurityConfig;
import com.example.visualchat.global.security.handler.ApiAccessDeniedHandler;
import com.example.visualchat.global.security.handler.ApiAuthenticationEntryPoint;
import com.example.visualchat.global.security.jwt.JwtConfig;
import com.example.visualchat.support.JwtTestFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.spec.SecretKeySpec;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 JWT 검증기와 필터 체인으로 헤더 인증·접근·세션 정책을 검증 */
@WebMvcTest
@ContextConfiguration(classes = {
        SecurityConfig.class, JwtConfig.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        SecurityConfigTest.ProbeController.class
})
@TestPropertySource(properties = {
        "jwt.issuer=" + JwtTestFixtures.ISSUER,
        "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtEncoder jwtEncoder;

    // [TOPIC] 공개 경로와 미인증 요청의 응답
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/signup", "/api/auth/login"})
    @DisplayName("공개된 인증 POST 경로는 인증·CSRF 토큰 없이 접근할 수 있다")
    void allowsAnonymousRequestsToAuthEndpoints(String path) throws Exception {
        mockMvc.perform(post(path)).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/private-test", "/api/auth/private", "/login"})
    @DisplayName("공개 경로 외 미인증 요청은 리다이렉트 없이 401과 공통 오류를 반환한다")
    void rejectsAnonymousRequestsToProtectedEndpoints(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.AUTHENTICATION_REQUIRED.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("로그인 경로라도 공개하지 않은 HTTP 메서드는 인증이 필요하다")
    void doesNotOpenEveryMethodOnLoginPath() throws Exception {
        mockMvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
    }

    // [TOPIC] 실제 서명된 토큰의 인증 성공과 검증 실패
    @Test
    @DisplayName("유효한 Bearer 토큰은 SecurityContext의 인증 이름으로 회원 ID를 제공한다")
    void authenticatesSignedBearerToken() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(validToken())))
                .andExpect(status().isOk()).andExpect(content().string("41"));
    }

    @Test
    @DisplayName("기본 시계 오차 허용 범위보다 오래 만료된 토큰은 401을 반환한다")
    void rejectsExpiredToken() throws Exception {
        Instant now = Instant.now();
        String token = JwtTestFixtures.sign(jwtEncoder, JwtTestFixtures.claims()
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(600)).build());
        assertInvalidToken(token);
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401을 반환한다")
    void rejectsForgedSignature() throws Exception {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(
                new SecretKeySpec(otherKey, "HmacSHA256")).build();
        assertInvalidToken(JwtTestFixtures.sign(otherEncoder, JwtTestFixtures.claims().build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "sub", "iat", "exp"})
    @DisplayName("필수 클레임이 누락된 토큰은 서명이 올바르더라도 거절한다")
    void rejectsMissingRequiredClaim(String claim) throws Exception {
        JwtClaimsSet claims = JwtTestFixtures.claims().claims(values -> values.remove(claim)).build();
        assertInvalidToken(JwtTestFixtures.sign(jwtEncoder, claims));
    }

    @Test
    @DisplayName("다른 발급자의 토큰은 서명이 올바르더라도 거절한다")
    void rejectsWrongIssuer() throws Exception {
        assertInvalidToken(JwtTestFixtures.sign(jwtEncoder,
                JwtTestFixtures.claims().issuer("another-service").build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-jwt", "a.b.c"})
    @DisplayName("JWT 형식이 잘못된 토큰은 서버 오류 대신 401을 반환한다")
    void rejectsMalformedJwt(String token) throws Exception {
        assertInvalidToken(token);
    }

    // [TOPIC] 헤더 외 인증 경로와 세션 재사용 차단
    @Test
    @DisplayName("쿠키에 있는 유효한 토큰도 인증 정보로 사용하지 않는다")
    void ignoresTokenInCookie() throws Exception {
        mockMvc.perform(get("/api/users/me").cookie(new Cookie("access_token", validToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("쿼리 파라미터에 있는 유효한 토큰도 인증 정보로 사용하지 않는다")
    void ignoresTokenInQueryParameter() throws Exception {
        mockMvc.perform(get("/api/users/me").param("access_token", validToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("폼 본문에 있는 유효한 토큰도 인증 정보로 사용하지 않는다")
    void ignoresTokenInFormBody() throws Exception {
        mockMvc.perform(post("/api/private-test").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("access_token=" + validToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("한 요청의 JWT 인증을 다음 요청의 세션 인증으로 재사용하지 않는다")
    void doesNotPersistAuthenticationInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/api/users/me").session(session)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validToken())))
                .andExpect(status().isOk());
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        mockMvc.perform(get("/api/users/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("세션에 저장된 인증 정보가 있어도 요청 인증으로 사용하지 않는다")
    void ignoresAuthenticationStoredInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "tester", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        mockMvc.perform(get("/api/users/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호 해시는 원문과 다르고 올바른 비밀번호만 검증에 성공한다")
    void encodesAndVerifiesPassword() {
        String rawPassword = "Test-password-123!";
        String encodedPassword = passwordEncoder.encode(rawPassword);
        assertThat(encodedPassword).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", encodedPassword)).isFalse();
    }

    private String validToken() {
        return JwtTestFixtures.sign(jwtEncoder, JwtTestFixtures.claims().build());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void assertInvalidToken(String token) throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_TOKEN.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_TOKEN.getMessage()));
    }

    /** [ROLE] 실제 필터 체인의 접근 정책을 관찰할 테스트 전용 API를 제공 */
    @RestController
    static class ProbeController {
        @PostMapping({"/api/auth/signup", "/api/auth/login"})
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void authEndpoint() {
        }

        @GetMapping({"/api/users/me", "/api/private-test"})
        String protectedEndpoint(Principal principal) {
            return principal.getName();
        }
    }
}
