package com.example.visualchat.common.response;

/** [ROLE] 입력 필드의 검증 실패 코드와 안내 메시지를 전달 */
public record FieldValidationError(String field, String code, String message) {
}