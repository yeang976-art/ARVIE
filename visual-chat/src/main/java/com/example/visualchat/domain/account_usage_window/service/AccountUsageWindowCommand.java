package com.example.visualchat.domain.account_usage_window.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** [ROLE] 현재 이용 주기의 선택·생성·동시 생성 충돌 복구를 조율 */
@Service
@RequiredArgsConstructor
public class AccountUsageWindowCommand {

    private final UserService userService;
    private final AccountUsageWindowService usageWindowService;
    private final Clock clock;

    // [WHY] 생성 실패가 롤백된 뒤 별도 트랜잭션에서 기존 주기를 조회
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UsageResponse getCurrentUsage(Long userId) {
        User user = userService.getById(userId);

        // [WHY] 처리 도중 주기 경계를 지나도 한 요청에서는 같은 기간을 사용
        Instant now = clock.instant();
        UsagePeriod period = UsagePeriod.from(user.getSignupCompletedAt(), now);

        Optional<UsageResponse> existing = usageWindowService.findByPeriod(userId, period.startAt());

        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return usageWindowService.create(user, period);
        } catch (DataIntegrityViolationException e) {
            // [CAUTION] 동일 회원·주기 시작 시각의 유일성 충돌만 복구
            if (!isDuplicatePeriod(e)) {
                throw e;
            }

            // [NOTE] 다른 요청이 먼저 커밋한 동일 주기를 조회
            return usageWindowService
                    .findByPeriod(userId, period.startAt())
                    .orElseThrow(() -> new ServiceException(ErrorCode.SERVER_ERROR));
        }
    }

    // [TOPIC] 동일 계정 이용 주기의 유일성 제약 위반 판별
    private boolean isDuplicatePeriod(Throwable error) {
        Throwable cause = error;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uk_account_usage_windows_user_period_start".equals(violation.getConstraintName())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}