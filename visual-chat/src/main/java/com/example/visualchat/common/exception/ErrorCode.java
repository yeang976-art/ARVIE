package com.example.visualchat.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "ERROR_COMMON_1", "잘못된 입력값"),
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ERROR_COMMON_2", "서버 오류"),

    INVALID_AI_REQUEST_STATUS(HttpStatus.BAD_REQUEST, "ERROR_AI_REQUEST_1", "유효하지 않은 AI 요청 상태");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
