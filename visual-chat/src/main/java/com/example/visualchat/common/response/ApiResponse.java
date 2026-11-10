package com.example.visualchat.common.response;

import com.example.visualchat.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/** [ROLE] 성공·실패 응답을 code·message·data 구조로 전달 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data) {

    private static final String SUCCESS_CODE = "SUCCESS";

    // [TOPIC] 성공 응답 생성
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }

    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(SUCCESS_CODE, message, null);
    }

    // [TOPIC] 오류 응답 생성
    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    // 커스텀 응답 메세지용
    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
