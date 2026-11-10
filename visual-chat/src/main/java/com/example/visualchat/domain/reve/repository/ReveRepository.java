package com.example.visualchat.domain.reve.repository;

import com.example.visualchat.domain.reve.entity.Reve;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** [ROLE] 레브 원본과 체험 배정 후보를 조회 */
public interface ReveRepository extends JpaRepository<Reve, Long> {

    // [WHY] 후보 순서를 일정하게 유지해 가중치 경계를 재현 가능하게 검증
    List<Reve> findAllByTrialEnabledTrueOrderByIdAsc();
}