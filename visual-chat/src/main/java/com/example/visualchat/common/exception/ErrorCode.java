package com.example.visualchat.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** [ROLE] 공통·인증·AI 요청 오류의 HTTP 상태와 응답 코드·메시지를 정의 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // [TOPIC] 공통 오류 코드
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "ERROR_COMMON_1", "잘못된 입력값"),
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_COMMON_2", "서버 오류"),

    // [TOPIC] 인증·인가 오류 코드
    // [NOTE] 존재하지 않는 계정과 비밀번호 불일치는 동일한 응답으로 처리
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_1", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_2", "로그인이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_3", "유효하지 않은 인증 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_4", "인증 토큰이 만료되었습니다. 다시 로그인해 주세요."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ERROR_AUTH_5", "해당 작업을 수행할 권한이 없습니다."),

    // [TOPIC] 회원 오류 코드
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "ERROR_USER_1", "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "ERROR_USER_2", "이미 사용 중인 닉네임입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "ERROR_USER_3", "회원 정보를 찾을 수 없습니다."),

    // [TOPIC] 레브 오류 코드
    TRIAL_REVE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ERROR_REVE_1", "현재 체험 레브를 배정할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_TRIAL_REVE_WEIGHT(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_REVE_2", "체험 레브의 배정 가중치 설정이 올바르지 않습니다."),

    // [TOPIC] AI 요청 오류 코드
    INVALID_AI_REQUEST_STATUS(HttpStatus.BAD_REQUEST, "ERROR_AI_REQUEST_1", "유효하지 않은 AI 요청 상태");

//  ======================================================================================================

    private final HttpStatus status;
    private final String code;
    private final String message;
}
