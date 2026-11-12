package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.model.AiRequestSnapshot;
import com.example.visualchat.domain.ai_request.model.IdempotencyKey;
import com.example.visualchat.domain.ai_request.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** [ROLE] AI 요청 기록의 저장과 소유권을 확인한 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiRequestService {

    private final AiRequestRepository aiRequestRepository;

    // [TOPIC] 새 AI 요청 기록 저장
    @Transactional
    public AiRequest create(AiRequest request) {
        // [WHY] 제약 위반을 저장 단계에서 확인하되 최종 커밋은 바깥 트랜잭션에서 수행
        return aiRequestRepository.saveAndFlush(request);
    }

    // [TOPIC] 요청 회원이 소유한 AI 요청 조회
    public AiRequest getOwnedRequest(Long userId, UUID requestId) {
        return aiRequestRepository
                .findByIdAndUser_Id(requestId, userId)
                .orElseThrow(() -> new ServiceException(ErrorCode.AI_REQUEST_NOT_FOUND));
    }

    // [TOPIC] 회원과 멱등키로 기존 요청 조회
    public Optional<AiRequestSnapshot> findByKey(Long userId, IdempotencyKey key) {
        // [WHY] 트랜잭션 밖의 접수 조율에는 필요한 값만 전달
        return aiRequestRepository
                .findByUser_IdAndIdempotencyKey(userId, key.value())
                .map(AiRequestSnapshot::from);
    }
}