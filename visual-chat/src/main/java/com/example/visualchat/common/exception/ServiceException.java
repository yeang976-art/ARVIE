package com.example.visualchat.common.exception;

import lombok.Getter;

/** [ROLE] 도메인 처리 실패의 오류 코드와 메시지를 전달하는 예외 */
@Getter
public class ServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public ServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
