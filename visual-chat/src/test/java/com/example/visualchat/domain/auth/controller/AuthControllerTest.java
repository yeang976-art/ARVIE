package com.example.visualchat.domain.auth.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.service.AuthCommand;
import com.example.visualchat.domain.auth.service.SignUpCommand;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import com.example.visualchat.domain.user.service.UserService;
import com.example.visualchat.global.config.SecurityConfig;
import com.example.visualchat.global.exception.GlobalExceptionHandler;
import com.example.visualchat.global.security.handler.ApiAccessDeniedHandler;
import com.example.visualchat.global.security.handler.ApiAuthenticationEntryPoint;
import com.example.visualchat.global.security.jwt.JwtConfig;
import com.example.visualchat.global.security.jwt.JwtTokenProvider;
import com.example.visualchat.support.AuthTestFixtures;
import com.example.visualchat.support.JwtTestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 실제 로그인 조율·비밀번호 검증·JWT 발급·보호 API 인증의 HTTP 흐름을 검증 */
@WebMvcTest
@ContextConfiguration(classes = {
        AuthController.class, AuthCommand.class, UserService.class,
        SecurityConfig.class, JwtConfig.class, JwtTokenProvider.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        GlobalExceptionHandler.class, AuthControllerTest.ProbeController.class
})
@TestPropertySource(properties = {
        "jwt.issuer=" + JwtTestFixtures.ISSUER,
        "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
class AuthControllerTest {

    @DynamicPropertySource
    static void registerAuthProperties(DynamicPropertyRegistry registry) {
        // [WHY] HTTP 인증 테스트가 실제 서버의 더미 해시 설정에 의존하지 않도록 분리
        registry.add("auth.dummy-password-hash", AuthTestFixtures::dummyPasswordHash);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private SignUpCommand signUpCommand;

    @Test
    @DisplayName("로그인에 성공하면 회원 ID와 만료 정보가 일치하는 토큰으로 보호 API를 호출할 수 있다")
    void logsInAndUsesReturnedToken() throws Exception {
        User user = User.of("tester", "user@example.com", new BCryptPasswordEncoder(4).encode("legacy-password"));
        ReflectionTestUtils.setField(user, "id", 41L);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(request("user@example.com", "legacy-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.data.token");
        String expiresAt = JsonPath.read(body, "$.data.expiresAt");
        var jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("41");
        assertThat(jwt.getExpiresAt()).isEqualTo(Instant.parse(expiresAt));
        mockMvc.perform(get("/api/test/authenticated-member")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("41"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("없는 이메일과 잘못된 비밀번호는 동일한 401 공통 응답을 반환한다")
    void returnsSameCredentialFailure(boolean existingUser) throws Exception {
        Optional<User> user = existingUser
                ? Optional.of(User.of("tester", "user@example.com", new BCryptPasswordEncoder(4).encode("correct")))
                : Optional.empty();
        when(userRepository.findByEmail("user@example.com")).thenReturn(user);
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(request("user@example.com", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_CREDENTIALS.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"identifier\":\"invalid-email\",\"password\":\"password\"}",
            "{\"identifier\":\"\",\"password\":\"password\"}",
            "{\"identifier\":\"user@example.com\",\"password\":\"\"}",
            "{\"identifier\":\"user@example.com\",\"password\":\"   \"}",
            "{\"identifier\":\"user@example.com\"}"
    })
    @DisplayName("필수값 또는 이메일 형식이 잘못되면 저장소 조회 전에 400을 반환한다")
    void validatesLoginRequestBeforeLookup(String json) throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()));
        verifyNoInteractions(userRepository);
    }

    private String request(String email, String password) {
        return objectMapper.writeValueAsString(Map.of("identifier", email, "password", password));
    }

    /** [ROLE] 로그인으로 발급받은 토큰의 인증 이름을 관찰하는 테스트 전용 API */
    @RestController
    static class ProbeController {
        @GetMapping("/api/test/authenticated-member")
        String member(Principal principal) {
            return principal.getName();
        }
    }
}
