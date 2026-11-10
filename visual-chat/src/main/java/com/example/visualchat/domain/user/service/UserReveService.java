package com.example.visualchat.domain.user.service;

import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserReveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [ROLE] 회원에게 선택된 레브를 배정하고 기록을 저장 */
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
}
