package com.example.visualchat.domain.auth.service;

import com.example.visualchat.domain.auth.dto.SignUpRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [ROLE] 회원가입 처리를 구현할 인증 도메인 서비스의 골격 */
@Service
@RequiredArgsConstructor
public class AuthService {

    @Transactional
    public void signUp(SignUpRequest request) {

    }
}
