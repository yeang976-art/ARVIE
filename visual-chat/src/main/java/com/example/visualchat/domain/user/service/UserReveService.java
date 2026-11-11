package com.example.visualchat.domain.user.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.dto.UserReveResponse;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [ROLE] 회원의 레브 배정과 배정 결과 조회를 담당 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserReveService {

    private final UserReveRepository userReveRepository;

    // [TOPIC] 최초 레브 배정 기록 생성
    @Transactional
    public UserReve assign(User user, Reve reve) {
        // [NOTE] 가입 Command에서 서버가 선택한 레브를 전달받음
        UserReve userReve = UserReve.of(user, reve);

        return userReveRepository.save(userReve);
    }

    // [TOPIC] 인증된 회원의 레브 배정 결과 조회
    public UserReveResponse getMyReve(Long userId) {
        UserReve userReve = userReveRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ServiceException(ErrorCode.USER_REVE_NOT_FOUND));

        // [WHY] 저장된 배정 결과를 사용해 반복 조회에도 같은 레브를 반환
        return UserReveResponse.from(userReve);
    }
}
