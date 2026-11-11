package com.example.visualchat.domain.user.repository;

import com.example.visualchat.domain.reve.entity.UserReve;
import org.springframework.data.jpa.repository.JpaRepository;

/** [ROLE] 회원의 레브 배정 기록을 저장 */
public interface UserReveRepository extends JpaRepository<UserReve, Long> {
}
