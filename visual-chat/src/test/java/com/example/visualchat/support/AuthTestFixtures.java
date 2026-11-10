package com.example.visualchat.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

/** [ROLE] 실제 환경변수 없이 인증 테스트에 사용할 더미 해시를 제공 */
public final class AuthTestFixtures {

    // [WHY] 고정 해시를 소스에 보관하지 않고 테스트 실행 중 강도 10으로 한 번 생성
    private static final String DUMMY_HASH =
            new BCryptPasswordEncoder(10).encode(UUID.randomUUID().toString());

    private AuthTestFixtures() {
    }

    public static String dummyPasswordHash() {
        return DUMMY_HASH;
    }
}
