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
import org.springframework.security.crypto.password.PasswordEncoder;
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
        // 접근 권한만 검증하며, CSRF 비활성화의 적절성은 별도 보안 검토 대상이다.
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/private-test"})
    @DisplayName("인증되지 않은 사용자는 보호 경로에 접근할 수 없다")
    void rejectsAnonymousRequestsToProtectedEndpoints(String path) throws Exception {
        // 별도 AuthenticationEntryPoint를 설정하기 전 현재 기본 응답은 403이다.
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("인증된 사용자는 보호 경로에 접근할 수 있다")
    void allowsAuthenticatedRequests() throws Exception {
        // 테스트 인증 정보를 주입한다. JWT 발급이나 토큰 검증을 테스트하는 것은 아니다.
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

    // 인증 정책을 실제 필터 체인으로 검증하기 위한 테스트 전용 엔드포인트다.
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
