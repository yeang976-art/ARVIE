package com.example.visualchat.domain.auth.controller;

import com.example.visualchat.common.response.ApiResponse;
import com.example.visualchat.domain.auth.dto.LoginRequest;
import com.example.visualchat.domain.auth.dto.LoginResponse;
import com.example.visualchat.domain.auth.dto.SignUpRequest;
import com.example.visualchat.domain.auth.service.AuthCommand;
import com.example.visualchat.domain.auth.service.SignUpCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

/** [ROLE] 인증 API의 요청을 검증하고 로그인 처리 결과를 공통 응답으로 반환 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthCommand authCommand;
    private final SignUpCommand signUpCommand;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signUp(@Valid @RequestBody SignUpRequest request) {
        signUpCommand.signUp(request);

        // [NOTE] Command의 트랜잭션 커밋이 완료된 뒤 성공 응답 생성
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("회원가입이 완료되었습니다."));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        // [NOTE] 로그인 검증과 토큰 발급 조율은 AuthCommand에 위임
        return ResponseEntity.ok(ApiResponse.ok(authCommand.login(request)));
    }
}
