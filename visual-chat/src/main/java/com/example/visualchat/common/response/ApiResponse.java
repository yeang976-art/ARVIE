package com.example.visualchat.common.response;

import com.example.visualchat.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/** [ROLE] API의 성공·실패 결과를 code·message·data 형식으로 전달 */
// [NOTE] 값이 없는 message와 data는 JSON 응답에서 제외
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

    // [NOTE] 필드별 검증 오류 등 상세 정보를 data에 포함해 전달
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.getCode(), message, data);
    }

    // [NOTE] 기본 오류 메시지를 호출자가 지정한 메시지로 대체
    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
