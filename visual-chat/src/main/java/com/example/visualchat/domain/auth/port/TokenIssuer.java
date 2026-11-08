package com.example.visualchat.domain.auth.port;

import com.example.visualchat.domain.auth.model.IssuedToken;

// [WHY] 인증 서비스가 global의 JWT 구현 클래스를 직접 참조하지 않도록 발급 기능을 추상화
public interface TokenIssuer {

    IssuedToken issue(Long userId);
}