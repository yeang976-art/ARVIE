package com.example.visualchat.domain.ai_request.util;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * [ROLE] 채팅방과 메시지 내용으로 요청 동일성을 비교할 지문을 생성
 */
@Component
public class AiRequestFingerprintGenerator {

    // [CAUTION] 기존 요청의 재시도 판정에 사용하므로 직렬화 규칙을 임의로 변경하지 않음
    private static final String FORMAT_VERSION = "arvie-ai-request:v1";

    public String generate(long roomId, String content) {
        if (roomId <= 0 || content == null || content.isBlank()) {
            throw new ServiceException(ErrorCode.INVALID_INPUT);
        }

        // [WHY] 정수인 채팅방 ID와 마지막 필드인 원문을 분리해 경계가 모호해지는 것을 방지
        // [NOTE] 공백·줄바꿈까지 요청 내용으로 취급하므로 원문을 trim하거나 정규화하지 않음
        String canonicalRequest = FORMAT_VERSION + "\n" + roomId + "\n" + content;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException exception) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }
    }
}