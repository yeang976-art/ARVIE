package com.example.visualchat.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank @Size(max = 50) String nickname,
        @NotBlank @Email String email,
        @NotBlank
        // 비밀번호는 8자 이상이며 대문자, 소문자, 숫자, 특수문자를 모두 포함해야함
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$")
        String password
) {
}
