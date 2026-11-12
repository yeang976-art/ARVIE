package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;

import java.util.regex.Pattern;

/** [ROLE] AI 요청 멱등키의 형식과 길이를 검증 */
public record IdempotencyKey(String value) {

    private static final Pattern VALID_FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    public IdempotencyKey {
        if (value == null || !VALID_FORMAT.matcher(value).matches()) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "멱등키는 영문·숫자·밑줄·하이픈으로 구성된 1~128자여야 합니다.");
        }
    }
}