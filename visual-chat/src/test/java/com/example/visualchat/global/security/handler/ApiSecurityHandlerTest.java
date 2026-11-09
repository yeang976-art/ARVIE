package com.example.visualchat.global.security.handler;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.support.JwtTestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** [ROLE] 인증·인가 오류의 상태·헤더·공통 JSON과 내부 오류 정보 비노출을 검증 */
class ApiSecurityHandlerTest {

    @Test
    @DisplayName("인증 누락은 401과 Bearer 인증 요구 헤더를 반환한다")
    void respondsToMissingAuthentication() throws Exception {
        var response = new MockHttpServletResponse();
        new ApiAuthenticationEntryPoint(JsonMapper.builder().build()).commence(
                new MockHttpServletRequest(), response, new InsufficientAuthenticationException("인증 정보 없음"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertJsonError(response, ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("토큰 검증 실패 응답에 예외 원문의 민감한 입력값을 포함하지 않는다")
    void doesNotExposeTokenValidationDetails() throws Exception {
        var response = new MockHttpServletResponse();
        String internalDetail = "token=" + JwtTestFixtures.SECRET;
        new ApiAuthenticationEntryPoint(JsonMapper.builder().build()).commence(
                new MockHttpServletRequest(), response, new InvalidBearerTokenException(internalDetail));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
        assertJsonError(response, ErrorCode.INVALID_TOKEN);
        assertThat(response.getContentAsString()).doesNotContain(internalDetail, JwtTestFixtures.SECRET);
    }

    @Test
    @DisplayName("권한 부족은 401과 구분되는 403 공통 오류를 반환한다")
    void respondsToAccessDenied() throws Exception {
        var response = new MockHttpServletResponse();
        new ApiAccessDeniedHandler(JsonMapper.builder().build()).handle(new MockHttpServletRequest(),
                response, new AccessDeniedException("내부 권한 정책 이름"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertJsonError(response, ErrorCode.ACCESS_DENIED);
        assertThat(response.getContentAsString()).doesNotContain("내부 권한 정책 이름");
    }

    private static void assertJsonError(MockHttpServletResponse response, ErrorCode errorCode) throws Exception {
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code")).isEqualTo(errorCode.getCode());
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.message")).isEqualTo(errorCode.getMessage());
    }
}
