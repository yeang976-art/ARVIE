package com.example.visualchat.domain.account_usage_window.dto;

import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;

import java.math.BigDecimal;
import java.time.Instant;

/** [ROLE] 계정의 현재 VIE 이용량과 이용 주기를 전달 */
public record UsageResponse(
        Long usageWindowId,
        BigDecimal allowanceVie,
        BigDecimal usedVie,
        BigDecimal reservedVie,
        BigDecimal remainingVie,
        Instant periodStartAt,
        Instant periodEndAt
) {
    public static UsageResponse from(AccountUsageWindow window) {
        return new UsageResponse(
                window.getId(),
                window.getAllowanceVie(),
                window.getUsedVie(),
                window.getReservedVie(),
                window.getRemainingVie(),
                window.getPeriodStartAt(),
                window.getPeriodEndAt()
        );
    }
}