package com.example.visualchat.global.security.handler;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** [ROLE] 인증 실패 시 401 */
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            @NonNull HttpServletRequest request,
            HttpServletResponse response,
            @NonNull AuthenticationException authException
    ) throws IOException {
        // [NOTE] Bearer 인증 실패와 인증 정보가 없는 요청을 구분
        boolean invalidToken = authException instanceof OAuth2AuthenticationException;

        ErrorCode errorCode = invalidToken ? ErrorCode.INVALID_TOKEN : ErrorCode.AUTHENTICATION_REQUIRED;

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // [NOTE] 클라이언트에 필요한 인증 방식과 토큰 오류 여부를 전달
        response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer"
        );

        // [WHY] 보안 필터의 인증 실패도 공통 API 응답 형식으로 반환
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode));
    }
}
