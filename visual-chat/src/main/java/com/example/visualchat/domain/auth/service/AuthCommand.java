package com.example.visualchat.domain.auth.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.auth.dto.LoginRequest;
import com.example.visualchat.domain.auth.dto.LoginResponse;
import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * [ROLE] 회원 조회·비밀번호 검증·토큰 발급을 조율하는 로그인 진입점
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthCommand {

    // [NOTE] 인코더와 동일한 강도(기본 10)로 미리 생성한 해시. 실제 비밀번호와 무관한 값.
    // [WHY] 회원이 없어도 BCrypt 비교를 수행해 응답 시간 차이를 줄임
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$tdd/pSXy5nV.VhE0nB8grOEPG0JS9cqeOcn4VlG04voltlRqfDI3W";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;

    public LoginResponse login(LoginRequest request) {
        // [NOTE] 회원 조회는 user 도메인의 서비스를 통해 수행
        Optional<User> found = userService.findByEmail(request.identifier());

        // [WHY] 회원이 없어도 동일한 비용의 해시 비교를 수행해 응답 시간 차이를 줄임
        String hash = found.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        // [WHY] 회원 조회 실패와 비밀번호 불일치에 동일한 오류 코드 사용
        if (found.isEmpty() || !passwordMatches) {
            throw new ServiceException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();

        // [NOTE] 인증에 성공한 회원의 ID로 Access Token 발급
        IssuedToken issuedToken = tokenIssuer.issue(user.getId());

        return LoginResponse.from(issuedToken);
    }
}
