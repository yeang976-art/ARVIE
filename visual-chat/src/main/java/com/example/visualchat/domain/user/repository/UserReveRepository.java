package com.example.visualchat.domain.user.repository;

import com.example.visualchat.domain.reve.entity.UserReve;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** [ROLE] 레브 배정 기록의 저장과 회원별 조회를 담당 */
public interface UserReveRepository extends JpaRepository<UserReve, Long> {

    // [WHY] 응답 변환에 필요한 레브를 함께 조회하도록 지정
    @EntityGraph(attributePaths = "reve")
    Optional<UserReve> findByUser_Id(Long userId);
}
