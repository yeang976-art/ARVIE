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

/** [ROLE] 회원 조회·비밀번호 검증·토큰 발급을 조율하는 로그인 진입점 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthCommand {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;

    public LoginResponse login(LoginRequest request) {
        // [NOTE] 회원 조회는 user 도메인의 서비스를 통해 수행
        User user = userService.findByEmail(request.identifier())
                .orElseThrow(() -> new ServiceException(ErrorCode.INVALID_CREDENTIALS));

        // [WHY] 입력 비밀번호를 다시 해시해 비교하지 않고 저장된 해시에 대해 검증
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.getPasswordHash());

        // [WHY] 회원 조회 실패와 비밀번호 불일치에 동일한 오류 코드 사용
        if (!passwordMatches) {
            throw new ServiceException(ErrorCode.INVALID_CREDENTIALS);
        }

        // [NOTE] 인증에 성공한 회원의 ID로 Access Token 발급
        IssuedToken issuedToken = tokenIssuer.issue(user.getId());

        return LoginResponse.from(issuedToken);
    }
}
