package com.example.visualchat.domain.account_usage_window.service;

import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.account_usage_window.repository.AccountUsageWindowRepository;
import com.example.visualchat.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [ROLE] 가입 회원의 최초 VIE 이용 주기를 생성하고 저장 */
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
}