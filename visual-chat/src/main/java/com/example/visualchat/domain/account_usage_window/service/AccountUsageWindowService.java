package com.example.visualchat.domain.account_usage_window.service;

import com.example.visualchat.domain.account_usage_window.dto.UsageResponse;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.model.UsagePeriod;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** [ROLE] 계정 이용 주기의 생성과 기간별 VIE 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountUsageWindowService {

    private final AccountUsageWindowRepository accountUsageWindowRepository;

    // [TOPIC] 최초 이용 주기 생성
    @Transactional
    public AccountUsageWindow createInitial(User user) {
        // [NOTE] 기간과 VIE 초기값은 엔티티의 생성 규칙을 사용
        AccountUsageWindow usageWindow = AccountUsageWindow.of(user);

        return accountUsageWindowRepository.save(usageWindow);
    }

    // [TOPIC] 회원과 주기 시작 시각으로 기존 이용 주기 조회
    public Optional<UsageResponse> findByPeriod(Long userId, Instant periodStartAt) {
        return accountUsageWindowRepository
                .findByUser_IdAndPeriodStartAt(userId, periodStartAt)
                .map(UsageResponse::from);
    }

    // [TOPIC] 현재 기간에 해당하는 이용 주기 생성
    @Transactional
    public UsageResponse create(User user, UsagePeriod period) {
        AccountUsageWindow window = AccountUsageWindow.of(user, period);

        // [WHY] 동시 주기 생성의 유일성 충돌을 저장 단계에서 확인
        AccountUsageWindow saved = accountUsageWindowRepository.saveAndFlush(window);

        return UsageResponse.from(saved);
    }
}