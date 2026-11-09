package com.example.visualchat;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import com.example.visualchat.support.JwtTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

// [WHY] 기동 검증이 개발용 DB에 접속하지 않도록 테스트 전용 메모리 DB를 사용한다.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-smoke-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.issuer=" + JwtTestFixtures.ISSUER,
        "jwt.secret=" + JwtTestFixtures.SECRET,
        "jwt.access-token-ttl=30m"
})
class VisualChatApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("애플리케이션 기동 시 영속성 구성과 보안 필터 체인이 함께 초기화된다")
    void initializesPersistenceAndSecurityConfiguration() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

}
