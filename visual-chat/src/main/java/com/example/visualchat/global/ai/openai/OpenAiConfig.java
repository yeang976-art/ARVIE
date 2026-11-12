package com.example.visualchat.global.ai.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/** [ROLE] OpenAI 외부 호출에 사용할 HTTP 클라이언트를 구성 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfig {

    @Bean(destroyMethod = "close")
    public HttpClient openAiHttpClient(OpenAiProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // [CAUTION] 인증 헤더가 다른 주소로 전달되지 않도록 리다이렉트를 따르지 않음
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}