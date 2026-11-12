package com.example.visualchat.domain.account_usage_window.model;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;

import java.time.Duration;
import java.time.Instant;

/** [ROLE] 가입 완료 시각을 기준으로 현재 이용 주기의 시작·종료 시각을 계산 */
public record UsagePeriod(Instant startAt, Instant endAt) {

    private static final Duration PERIOD = Duration.ofDays(30);

    public static UsagePeriod from(Instant signupCompletedAt, Instant now) {
        // [CAUTION] 서버의 가입 시각과 현재 시각이 유효하지 않으면 내부 오류로 처리
        if (signupCompletedAt == null || now == null || now.isBefore(signupCompletedAt)) {
            throw new ServiceException(ErrorCode.SERVER_ERROR);
        }

        // [WHY] 장기 미접속 후에도 가입 시각을 기준으로 동일한 주기를 선택
        long elapsedPeriods = Duration.between(signupCompletedAt, now).dividedBy(PERIOD);

        Instant startAt = signupCompletedAt.plus(PERIOD.multipliedBy(elapsedPeriods));

        // [NOTE] 시작 시각은 포함하고 종료 시각부터는 다음 주기로 처리
        return new UsagePeriod(startAt, startAt.plus(PERIOD));
    }
}