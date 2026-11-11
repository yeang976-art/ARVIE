package com.example.visualchat.domain.auth.controller;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.auth.dto.SignUpRequest;
import com.example.visualchat.domain.auth.service.AuthCommand;
import com.example.visualchat.domain.auth.service.SignUpCommand;
import com.example.visualchat.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [ROLE] 가입 요청 검증·필드별 한국어 메시지·비밀번호 비노출을 검증 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {AuthController.class, GlobalExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class SignUpControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AuthCommand authCommand;
    @MockitoBean private SignUpCommand signUpCommand;

    @Test
    @DisplayName("유효한 요청은 가입 Command에 전달하고 비밀번호 없이 201 응답을 반환한다")
    void acceptsValidRequest() throws Exception {
        SignUpRequest request = request("tester", "user@example.com", "Signup1!");
        String response = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "nickname", request.nickname(), "email", request.email(), "password", request.password()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        verify(signUpCommand).signUp(request);
        assertThat(response).doesNotContain(request.password()).doesNotContain("passwordHash");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidRequests")
    @DisplayName("잘못된 가입 입력은 서비스 호출 전에 필드·검증 코드·한국어 메시지로 안내한다")
    void validatesInput(String label, String nickname, String email, String password,
                       String field, String code, String message) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("nickname", nickname);
        body.put("email", email);
        body.put("password", password);
        mockMvc.perform(post("/api/auth/signup").locale(Locale.KOREAN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.getCode()))
                .andExpect(jsonPath("$.data[?(@.field == '" + field + "' && @.code == '" + code + "')].message",
                        hasItem(message)))
                .andExpect(jsonPath("$.data[*].rejectedValue").isEmpty());
        verifyNoInteractions(signUpCommand, authCommand);
    }

    static Stream<Arguments> invalidRequests() {
        String validEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        return Stream.of(
                Arguments.of("빈 닉네임", "", "user@example.com", "Signup1!", "nickname", "NotBlank", "닉네임을 입력해 주세요."),
                Arguments.of("닉네임 길이 초과", "가".repeat(51), "user@example.com", "Signup1!", "nickname", "Size", "닉네임은 50자 이하여야 합니다."),
                Arguments.of("이메일 형식", "tester", "invalid", "Signup1!", "email", "Email", "올바른 이메일 형식으로 입력해 주세요."),
                Arguments.of("이메일 길이 초과", "tester", validEmail + "d", "Signup1!", "email", "Size", "이메일은 254자 이하여야 합니다."),
                Arguments.of("비밀번호 누락", "tester", "user@example.com", null, "password", "NotBlank", "비밀번호를 입력해 주세요."),
                Arguments.of("짧은 비밀번호", "tester", "user@example.com", "Aa1!abc", "password", "Size", "비밀번호는 8자 이상 72자 이하여야 합니다."),
                Arguments.of("긴 비밀번호", "tester", "user@example.com", "Aa1!" + "x".repeat(69), "password", "Size", "비밀번호는 8자 이상 72자 이하여야 합니다."),
                Arguments.of("대문자 누락", "tester", "user@example.com", "lowercase1!", "password", "Pattern", "비밀번호는 대문자·소문자·숫자·허용된 특수문자를 모두 포함해야 합니다."),
                Arguments.of("소문자 누락", "tester", "user@example.com", "UPPERCASE1!", "password", "Pattern", "비밀번호는 대문자·소문자·숫자·허용된 특수문자를 모두 포함해야 합니다."),
                Arguments.of("숫자 누락", "tester", "user@example.com", "Password!!", "password", "Pattern", "비밀번호는 대문자·소문자·숫자·허용된 특수문자를 모두 포함해야 합니다."),
                Arguments.of("특수문자 누락", "tester", "user@example.com", "Password12", "password", "Pattern", "비밀번호는 대문자·소문자·숫자·허용된 특수문자를 모두 포함해야 합니다.")
        );
    }

    @Test
    @DisplayName("허용된 최대 길이의 가입 입력도 201로 처리한다")
    void acceptsMaximumLengths() throws Exception {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "nickname", "가".repeat(50), "email", email, "password", "Aa1!" + "x".repeat(68)))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("입력 비밀번호는 검증 실패 응답과 애플리케이션 로그에 포함하지 않는다")
    void excludesRejectedPasswordFromLogs(CapturedOutput output) throws Exception {
        String raw = "Hidden-password-without-number!";
        String response = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "nickname", "tester", "email", "user@example.com", "password", raw))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(raw);
        assertThat(output.getAll()).doesNotContain(raw);
    }

    @Test
    @DisplayName("가입 DTO는 JSON 입력으로 비밀번호를 읽고 직렬화와 문자열 표현에서는 제외한다")
    void keepsPasswordWriteOnly() throws Exception {
        SignUpRequest request = objectMapper.readValue(
                "{\"nickname\":\"tester\",\"email\":\"user@example.com\",\"password\":\"Signup1!\"}", SignUpRequest.class);
        assertThat(request.password()).isEqualTo("Signup1!");
        assertThat(objectMapper.writeValueAsString(request)).doesNotContain("password").doesNotContain(request.password());
        assertThat(request.toString()).doesNotContain(request.password());
    }

    private SignUpRequest request(String nickname, String email, String password) {
        return new SignUpRequest(nickname, email, password);
    }
}
