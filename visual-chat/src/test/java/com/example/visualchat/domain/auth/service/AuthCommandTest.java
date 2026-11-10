package com.example.visualchat.domain.auth.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.auth.dto.LoginRequest;
import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** [ROLE] 로그인 조율의 성공·실패와 인증 실패 시 토큰 미발급을 검증 */
class AuthCommandTest {

    private final UserService userService = mock(UserService.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    // [NOTE] 테스트용 회원 해시만 강도를 낮추며 더미 해시는 운영 코드의 강도로 비교
    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private AuthCommand command;

    @BeforeEach
    void setUp() {
        command = new AuthCommand(userService, passwordEncoder, tokenIssuer);
    }

    @Test
    @DisplayName("비밀번호가 일치하면 조회한 회원 ID로 발급하고 토큰과 만료 시각을 반환한다")
    void logsInWithStoredPasswordHash() {
        User user = user();
        Instant expiresAt = Instant.parse("2027-01-01T00:30:00Z");
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenIssuer.issue(41L)).thenReturn(new IssuedToken("issued-token", expiresAt));

        var response = command.login(new LoginRequest("user@example.com", "existing-password"));

        assertThat(response.token()).isEqualTo("issued-token");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        verify(tokenIssuer).issue(41L);
    }

    @Test
    @DisplayName("등록되지 않은 이메일도 유효한 강도 10의 BCrypt 해시와 비교하고 인증에 실패한다")
    void rejectsUnknownMember() {
        when(userService.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> command.login(new LoginRequest("missing@example.com", "password")))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getMessage());
                });

        // [WHY] 응답 시간 측정 대신 실제 비교 호출과 해시 형식을 검사해 불안정한 시간 기반 검증을 피함
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("password"), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).matches("\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}");
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    @DisplayName("더미 비밀번호 비교가 성공해도 회원이 없으면 인증을 거절하고 토큰을 발급하지 않는다")
    void rejectsUnknownMemberEvenWhenPasswordMatches() {
        PasswordEncoder acceptingEncoder = mock(PasswordEncoder.class);
        when(acceptingEncoder.matches(anyString(), anyString())).thenReturn(true);
        AuthCommand commandWithMatchingPassword = new AuthCommand(userService, acceptingEncoder, tokenIssuer);
        when(userService.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> commandWithMatchingPassword.login(
                        new LoginRequest("missing@example.com", "matching-password")))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getMessage());
                });

        verify(acceptingEncoder).matches(eq("matching-password"), anyString());
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    @DisplayName("잘못된 비밀번호도 동일한 로그인 실패로 처리하고 토큰을 발급하지 않는다")
    void rejectsWrongPassword() {
        User user = user();
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> command.login(new LoginRequest("user@example.com", "wrong-password")))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getMessage());
                });
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    @DisplayName("토큰 발급 실패를 로그인 성공으로 반환하지 않는다")
    void propagatesTokenIssuanceFailure() {
        User user = user();
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenIssuer.issue(41L)).thenThrow(new IllegalStateException("토큰 발급 실패"));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> command.login(new LoginRequest("user@example.com", "existing-password")))
                .withMessage("토큰 발급 실패");
    }

    private User user() {
        User user = User.of("tester", "user@example.com", passwordEncoder.encode("existing-password"));
        ReflectionTestUtils.setField(user, "id", 41L);
        return user;
    }
}
