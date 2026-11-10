package com.example.visualchat.global.config;

import com.example.visualchat.global.security.handler.ApiAccessDeniedHandler;
import com.example.visualchat.global.security.handler.ApiAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * [ROLE] 비밀번호 인코더와 요청 접근·세션 정책을 구성
 */
@Configuration
public class SecurityConfig {

    // [TOPIC] 비밀번호 해시 생성·검증 구성
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // 비밀번호를 단방향 해시로 저장
    }

    // [TOPIC] Bearer 인증과 인증·인가 실패 응답 연결
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        // [NOTE] Authorization 헤더에서 Bearer 토큰을 추출
        DefaultBearerTokenResolver tokenResolver = new DefaultBearerTokenResolver();

        // [WHY] 토큰 전달 경로를 헤더로 제한
        tokenResolver.setAllowFormEncodedBodyParameter(false);
        tokenResolver.setAllowUriQueryParameter(false);

        return http
                // [WHY] 인증 정보를 쿠키에서 읽지 않고 Authorization 헤더로만 전달하는 API 정책
                .csrf(AbstractHttpConfigurer::disable)

                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // [NOTE] 인증 정보를 HTTP 세션에 저장하거나 세션에서 복원하지 않음
                .sessionManagement(
                        session
                                -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authorizeHttpRequests(
                        auth
                                -> auth.requestMatchers(
                                        HttpMethod.POST,
                                        "/api/auth/signup",
                                        "/api/auth/login"
                                )
                                .permitAll()
                                .anyRequest()
                                .authenticated()
                )

                // [NOTE] 보호 경로 접근 시 인증 누락·권한 부족 응답 처리
                .exceptionHandling(
                        exception
                                -> exception.authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler)
                )

                // [NOTE] Bearer 토큰 추출·JWT 검증·인증 정보 등록을 Spring Security에 연결
                .oauth2ResourceServer(
                        resourceServer
                                -> resourceServer.bearerTokenResolver(tokenResolver)
                                .jwt(jwt -> jwt.decoder(jwtDecoder))
                                .authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler)
                )

                .build();
    }
}
