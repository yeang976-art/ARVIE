package com.example.visualchat.domain.ai_request.repository;

import com.example.visualchat.domain.ai_request.entity.AiRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** [ROLE] AI 요청 기록의 저장과 소유 회원 기준 조회를 담당 */
public interface AiRequestRepository extends JpaRepository<AiRequest, UUID> {

    // [WHY] 요청 식별자와 회원을 함께 조회해 다른 회원의 요청 접근을 차단
    Optional<AiRequest> findByIdAndUser_Id(UUID requestId, Long userId);

    // [WHY] 멱등키의 유일성 범위를 회원 단위로 제한
    Optional<AiRequest> findByUser_IdAndIdempotencyKey(Long userId, String idempotencyKey);
}