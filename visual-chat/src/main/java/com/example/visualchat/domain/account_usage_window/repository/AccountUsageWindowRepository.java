package com.example.visualchat.domain.account_usage_window.repository;

import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import org.springframework.data.jpa.repository.JpaRepository;

/** [ROLE] 계정의 VIE 이용 주기를 저장 */
public interface AccountUsageWindowRepository extends JpaRepository<AccountUsageWindow, Long> {
}
