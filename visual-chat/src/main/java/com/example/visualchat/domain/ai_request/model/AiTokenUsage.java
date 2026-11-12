package com.example.visualchat.domain.ai_request.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;

/** [ROLE] AI 제공자가 보고한 과금 대상 토큰 사용량을 보관 */
public record AiTokenUsage(
        long inputTokens,
        long cachedInputTokens,
        long cacheWrite30mTokens,
        long cacheWrite12hTokens,
        long outputTokens
) {
    public AiTokenUsage {
        if (inputTokens < 0 || cachedInputTokens < 0
                || cacheWrite30mTokens < 0 || cacheWrite12hTokens < 0
                || outputTokens < 0) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "토큰 사용량은 0 이상이어야 합니다.");
        }

        // [WHY] 직접 합산할 때 발생할 수 있는 정수 오버플로를 피하면서 부분 사용량을 검증
        if (cachedInputTokens > inputTokens
                || cacheWrite30mTokens > inputTokens - cachedInputTokens
                || cacheWrite12hTokens
                > inputTokens - cachedInputTokens - cacheWrite30mTokens) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "캐시 토큰의 합계가 전체 입력 토큰을 초과했습니다.");
        }
    }

    // [WHY] 캐시 읽기·쓰기 토큰을 일반 입력으로 중복 계산하지 않음
    public long ordinaryInputTokens() {
        return inputTokens - cachedInputTokens - cacheWrite30mTokens - cacheWrite12hTokens;
    }
}