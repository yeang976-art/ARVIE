package com.example.visualchat.domain.auth.port;

import com.example.visualchat.domain.auth.model.IssuedToken;

/** [ROLE] 인증 도메인이 사용하는 회원 ID 기반 토큰 발급 계약을 정의 */
// [WHY] 인증 서비스가 global의 JWT 구현 클래스를 직접 참조하지 않도록 발급 기능을 추상화
public interface TokenIssuer {

    IssuedToken issue(Long userId);
}