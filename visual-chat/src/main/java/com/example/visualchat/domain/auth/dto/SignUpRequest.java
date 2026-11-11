package com.example.visualchat.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** [ROLE] 회원가입 입력값을 전달하고 필수값·이메일·비밀번호 형식을 검증 */
public record SignUpRequest(
        @NotBlank
        @Size(max = 50)
        String nickname,

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 8, max = 72) // [WHY] 현재 허용하는 ASCII 문자 기준으로 BCrypt의 72바이트 제한을 준수
        @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) // [CAUTION] JSON 입력은 허용하되 직렬화 결과에는 비밀번호를 포함하지 않음
        String password
) {
        // [WHY] record의 기본 문자열 표현으로 비밀번호가 로그에 노출되는 것을 방지
        @Override
        public String toString() {
                return "SignUpRequest[nickname=" + nickname + ", email=" + email + "]";
        }
}
