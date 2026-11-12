package com.example.visualchat.domain.account_usage_window.repository;

import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** [ROLE] 계정별 이용 주기의 저장과 기간별 조회를 담당 */
public interface AccountUsageWindowRepository extends JpaRepository<AccountUsageWindow, Long> {

    // [TOPIC] 회원과 주기 시작 시각으로 이용 주기 조회
    Optional<AccountUsageWindow> findByUser_IdAndPeriodStartAt(Long userId, Instant periodStartAt);
}
