package com.example.visualchat.domain.auth.service;

import com.example.visualchat.domain.account_usage_window.service.AccountUsageWindowService;
import com.example.visualchat.domain.auth.dto.SignUpRequest;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.service.ReveService;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.domain.user.service.UserReveService;
import com.example.visualchat.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [ROLE] 회원 생성·최초 레브 배정·이용 주기 생성을 조율 */
@Service
@RequiredArgsConstructor
public class SignUpCommand {

    private final UserService userService;
    private final ReveService reveService;
    private final UserReveService userReveService;
    private final AccountUsageWindowService accountUsageWindowService;
    private final PasswordEncoder passwordEncoder;

    // [TOPIC] 회원가입 및 최초 이용 데이터 생성
    // [WHY] 배정이나 이용 주기 생성 실패 시 회원 생성까지 함께 롤백
    @Transactional
    public void signUp(SignUpRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());

        User user = userService.create(request.nickname(), request.email(), passwordHash);

        // [NOTE] 배정 대상은 클라이언트 입력 없이 서버에서 선택
        Reve selectedReve = reveService.selectTrialReve();

        userReveService.assign(user, selectedReve);
        accountUsageWindowService.createInitial(user);
    }
}