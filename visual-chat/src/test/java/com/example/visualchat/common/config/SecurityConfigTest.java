package com.example.visualchat.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/signup", "/api/auth/login"})
    @DisplayName("회원가입과 로그인 경로는 인증 없이 접근할 수 있다")
    void allowsAnonymousRequestsToAuthEndpoints(String path) throws Exception {
        // [NOTE] 현재 CSRF 방어가 활성화되어 있으므로 유효한 토큰을 전달해 접근 권한을 검증한다.
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/private-test"})
    @DisplayName("인증되지 않은 사용자는 보호 경로에 접근할 수 없다")
    void rejectsAnonymousRequestsToProtectedEndpoints(String path) throws Exception {
        // [NOTE] 별도 AuthenticationEntryPoint를 설정하기 전 현재 기본 응답은 403이다.
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("인증된 사용자는 보호 경로에 접근할 수 있다")
    void allowsAuthenticatedRequests() throws Exception {
        // [NOTE] 테스트 인증 정보를 주입하며 JWT 발급과 검증은 후속 토큰 테스트에서 다룬다.
        mockMvc.perform(get("/api/users/me").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(content().string("tester"));
    }

    @Test
    @DisplayName("한 요청의 인증 정보는 다음 요청의 HTTP 세션 인증으로 재사용되지 않는다")
    void doesNotPersistAuthenticationInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/users/me").session(session).with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(content().string("tester"));

        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("세션에 저장된 인증 정보가 있어도 보호 경로의 인증으로 사용하지 않는다")
    void ignoresAuthenticationStoredInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "tester", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));

        mockMvc.perform(get("/api/users/me").session(session))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/signup", "/api/auth/login"})
    @DisplayName("인증 없이 열린 경로라도 CSRF 토큰이 없는 상태 변경 요청은 거절한다")
    void rejectsAuthRequestsWithoutCsrfToken(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("스프링 기본 로그인 페이지를 제공하지 않는다")
    void doesNotExposeDefaultLoginPage() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
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

    // [WHY] 인증 정책을 실제 필터 체인으로 검증하기 위해 테스트 전용 엔드포인트를 제공한다.
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
