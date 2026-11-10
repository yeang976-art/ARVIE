package com.example.visualchat.domain.user.service;

import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** [ROLE] 회원 저장소를 통해 user 도메인의 조회 기능을 제공 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}
