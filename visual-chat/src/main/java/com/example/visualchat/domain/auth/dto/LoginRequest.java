package com.example.visualchat.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank
        @Email
        String identifier,

        @NotBlank
        // [WHY] 비밀번호 복잡도는 가입 시 검증하고 로그인에서는 저장된 해시와 일치하는지 확인
        // @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\\\d)(?=.*[@$!%*?&])[A-Za-z\\\\d@$!%*?&]{8,}$")
        String password
) {
}
