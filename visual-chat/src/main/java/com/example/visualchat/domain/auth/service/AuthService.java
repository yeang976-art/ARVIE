package com.example.visualchat.domain.auth.service;

import com.example.visualchat.domain.auth.dto.SignUpRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    @Transactional
    public void signUp(SignUpRequest request) {

    }
}
