package com.example.visualchat.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** [ROLE] 비밀번호 인코더와 요청 접근·세션 정책을 구성 */
@Configuration
public class SecurityConfig {

    // [TOPIC] 비밀번호 해시 생성·검증 구성
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // 비밀번호를 단방향 해시로 저장
    }

    // [TOPIC] 요청 접근과 세션 정책 구성
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                //.csrf(AbstractHttpConfigurer::disable) // 세션 쿠키 대신 토큰을 헤더로 보내는 API라 CSRF 방어 비활성화 (JWT 쓰니)
                .formLogin(AbstractHttpConfigurer::disable) // 스프링 기본 로그인 페이지 말고 JSON API로 로그인하므로 비활성화
                .httpBasic(AbstractHttpConfigurer::disable) // 아이디·비번을 매 요청 헤더에 담는 Basic 인증 비활성화
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // JWT식 로그인이라 서버가 세션을 기억 못함
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll() // 모든 사람한테 열어두기
                        .anyRequest().authenticated()) // 인증을 거쳐야함
                .build();
    }
}
