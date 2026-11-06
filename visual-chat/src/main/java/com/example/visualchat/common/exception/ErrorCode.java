package com.example.visualchat.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
//  ========================================< COMMON >==================================================
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "ERROR_COMMON_1", "잘못된 입력값"),
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_COMMON_2", "서버 오류"),

//  =========================================< AUTH >===================================================
    // [NOTE] 존재하지 않는 계정과 비밀번호 불일치는 동일한 응답으로 처리
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_1", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_2", "로그인이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_3", "유효하지 않은 인증 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_4", "인증 토큰이 만료되었습니다. 다시 로그인해 주세요."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ERROR_AUTH_5", "해당 작업을 수행할 권한이 없습니다."),

//  ======================================< AI_REQUEST >=================================================
    INVALID_AI_REQUEST_STATUS(HttpStatus.BAD_REQUEST, "ERROR_AI_REQUEST_1", "유효하지 않은 AI 요청 상태");

//  ======================================================================================================

    private final HttpStatus status;
    private final String code;
    private final String message;
}
