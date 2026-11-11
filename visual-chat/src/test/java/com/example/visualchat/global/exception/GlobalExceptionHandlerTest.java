package com.example.visualchat.global.exception;

import com.example.visualchat.common.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** [ROLE] 중첩된 DB 예외의 SQLState·제약 이름에 따른 안전한 오류 변환을 검증 */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());

    @ParameterizedTest
    @CsvSource({
            "23505,uk_users_email,DUPLICATE_EMAIL",
            "23505,uk_users_nickname,DUPLICATE_NICKNAME",
            "23505,unknown_constraint,SERVER_ERROR",
            "23505,,SERVER_ERROR",
            "23502,uk_users_email,SERVER_ERROR"
    })
    @DisplayName("유일성 위반과 정확한 회원 제약 이름이 일치할 때만 중복 오류로 변환한다")
    void mapsNestedConstraintViolation(String sqlState, String constraintName, ErrorCode expected) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getSQLState()).thenReturn(sqlState);
        when(violation.getConstraintName()).thenReturn(constraintName);
        var exception = new DataIntegrityViolationException("민감한 DB 입력값", new IllegalStateException("중첩 예외", violation));

        var response = handler.handleDataIntegrityViolationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(expected.getStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(expected.getCode());
        assertThat(response.getBody().message()).isEqualTo(expected.getMessage()).doesNotContain("민감한 DB 입력값");
    }
}
