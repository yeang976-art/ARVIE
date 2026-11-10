package com.example.visualchat.domain.reve.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** [ROLE] 체험 배정 대상 레브를 조회 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReveService {

    private final ReveRepository reveRepository;

    // [TOPIC] 활성 체험 후보 조회
    public List<Reve> findTrialCandidates() {
        List<Reve> candidates = reveRepository.findAllByTrialEnabledTrueOrderByIdAsc();

        if (candidates.isEmpty()) {
            throw new ServiceException(ErrorCode.TRIAL_REVE_UNAVAILABLE);
        }

        return candidates;
    }
}