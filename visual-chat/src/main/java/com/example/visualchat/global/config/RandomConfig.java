package com.example.visualchat.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** [ROLE] 체험 레브 선택에 사용할 난수 생성기를 구성 */
@Configuration
public class RandomConfig {

    @Bean
    public RandomGenerator trialReveRandomGenerator() {
        return new SecureRandom();
    }
}