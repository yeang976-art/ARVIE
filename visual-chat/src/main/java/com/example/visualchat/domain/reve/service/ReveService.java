package com.example.visualchat.domain.reve.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.random.RandomGenerator;

/** [ROLE] 체험 배정 대상 레브를 조회 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReveService {

    private final ReveRepository reveRepository;
    private final RandomGenerator randomGenerator;

    // [TOPIC] 활성 체험 후보 조회
    public List<Reve> findTrialCandidates() {
        List<Reve> candidates = reveRepository.findAllByTrialEnabledTrueOrderByIdAsc();

        if (candidates.isEmpty()) {
            throw new ServiceException(ErrorCode.TRIAL_REVE_UNAVAILABLE);
        }

        return candidates;
    }

    // [TOPIC] 상대 가중치에 따른 체험 레브 선택
    public Reve selectTrialReve() {
        List<Reve> candidates = findTrialCandidates();

        BigDecimal totalWeight = BigDecimal.ZERO;

        for (Reve candidate : candidates) {
            BigDecimal weight = candidate.getTrialWeight();

            if (weight == null || weight.signum() <= 0) {
                throw new ServiceException(ErrorCode.INVALID_TRIAL_REVE_WEIGHT);
            }

            totalWeight = totalWeight.add(weight);
        }

        // [NOTE] 0 이상 1 미만의 난수를 전체 가중치 구간의 선택 지점으로 변환
        BigDecimal selectionPoint = BigDecimal.valueOf(randomGenerator.nextDouble()).multiply(totalWeight);

        BigDecimal cumulativeWeight = BigDecimal.ZERO;

        for (Reve candidate : candidates) {
            cumulativeWeight = cumulativeWeight.add(candidate.getTrialWeight());

            // [WHY] 구간 끝을 제외해 경계값이 다음 후보에 속하도록 처리
            if (selectionPoint.compareTo(cumulativeWeight) < 0) {
                return candidate;
            }
        }

        // [CAUTION] 정상적인 후보·가중치·난수 범위에서는 도달하지 않는 상태
        throw new ServiceException(ErrorCode.TRIAL_REVE_UNAVAILABLE);
    }
}