package com.example.visualchat.domain.auth.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.auth.dto.LoginRequest;
import com.example.visualchat.domain.auth.dto.LoginResponse;
import com.example.visualchat.domain.auth.model.IssuedToken;
import com.example.visualchat.domain.auth.port.TokenIssuer;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * [ROLE] 회원 조회·비밀번호 검증·토큰 발급을 조율하는 로그인 진입점
 */
@Service
@Transactional(readOnly = true)
public class AuthCommand {
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final String dummyPasswordHash;

    // [WHY] 설정값을 받는 매개변수에 @Value를 지정하기 위해 생성자를 직접 작성
    // [CAUTION] @RequiredArgsConstructor를 함께 사용하면 동일한 생성자가 중복 생성됨
    public AuthCommand(
            UserService userService,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            @Value("${auth.dummy-password-hash}") String dummyPasswordHash
    ) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyPasswordHash = dummyPasswordHash;
    }

    public LoginResponse login(LoginRequest request) {
        // [NOTE] 회원 조회는 user 도메인의 서비스를 통해 수행
        Optional<User> found = userService.findByEmail(request.identifier());

        // [WHY] 회원이 없어도 동일한 비용의 해시 비교를 수행해 응답 시간 차이를 줄임
        String hash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
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
