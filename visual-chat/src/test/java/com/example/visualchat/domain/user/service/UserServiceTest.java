package com.example.visualchat.domain.user.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [ROLE] 본인 정보 조회의 DTO 변환과 존재하지 않는 회원의 오류 처리를 검증 */
class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    @DisplayName("회원 ID로 조회한 회원의 기본 정보와 가입 완료 시각을 DTO로 반환한다")
    void returnsMemberInformation() {
        User user = User.of("tester", "User@example.com", "encoded-test-value");
        Instant signupCompletedAt = Instant.parse("2026-11-11T00:00:00Z");
        ReflectionTestUtils.setField(user, "id", 41L);
        ReflectionTestUtils.setField(user, "signupCompletedAt", signupCompletedAt);
        when(userRepository.findById(41L)).thenReturn(Optional.of(user));

        var response = userService.getMyInfo(41L);

        assertThat(response.userId()).isEqualTo(41L);
        assertThat(response.nickname()).isEqualTo("tester");
        assertThat(response.email()).isEqualTo("User@example.com");
        assertThat(response.signupCompletedAt()).isEqualTo(signupCompletedAt);
        verify(userRepository).findById(41L);
    }

    @Test
    @DisplayName("조회한 회원이 없으면 회원 조회 실패 오류를 반환한다")
    void rejectsMissingMember() {
        when(userRepository.findById(41L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> userService.getMyInfo(41L))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.USER_NOT_FOUND.getMessage());
                });
    }
}
