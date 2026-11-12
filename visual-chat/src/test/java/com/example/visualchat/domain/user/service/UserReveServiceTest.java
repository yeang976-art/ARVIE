package com.example.visualchat.domain.user.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [ROLE] 저장된 레브 배정의 공개 정보 변환과 배정 없음 오류를 검증 */
class UserReveServiceTest {

    private final UserReveRepository repository = mock(UserReveRepository.class);
    private final UserReveService service = new UserReveService(repository);

    @ParameterizedTest
    @EnumSource(value = Mbti.class, names = "ENFP")
    @NullSource
    @DisplayName("배정된 레브의 공개 필드와 선택적인 MBTI를 DTO로 반환한다")
    void returnsStoredAssignment(Mbti mbti) {
        User owner = User.of("tester", "tester@example.com", "encoded-test-value");
        Reve reve = Reve.of("REVE_TEST", "루미", mbti, "레브 소개", "서버 전용 프롬프트", BigDecimal.ONE);
        UserReve assignment = UserReve.of(owner, reve);
        Instant assignedAt = Instant.parse("2026-11-11T00:00:00Z");
        ReflectionTestUtils.setField(reve, "id", 73L);
        ReflectionTestUtils.setField(assignment, "id", 89L);
        ReflectionTestUtils.setField(assignment, "assignedAt", assignedAt);
        when(repository.findByUser_Id(41L)).thenReturn(Optional.of(assignment));

        var response = service.getMyReve(41L);

        assertThat(response.userReveId()).isEqualTo(89L);
        assertThat(response.reveId()).isEqualTo(73L);
        assertThat(response.name()).isEqualTo("루미");
        assertThat(response.mbti()).isEqualTo(mbti);
        assertThat(response.description()).isEqualTo("레브 소개");
        assertThat(response.assignedAt()).isEqualTo(assignedAt);
        verify(repository).findByUser_Id(41L);
    }

    @Test
    @DisplayName("회원의 배정 기록이 없으면 배정 없음 오류를 반환한다")
    void rejectsMissingAssignment() {
        when(repository.findByUser_Id(41L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> service.getMyReve(41L))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.USER_REVE_NOT_FOUND);
                    assertThat(error.getMessage()).isEqualTo(ErrorCode.USER_REVE_NOT_FOUND.getMessage());
                });
    }
}
