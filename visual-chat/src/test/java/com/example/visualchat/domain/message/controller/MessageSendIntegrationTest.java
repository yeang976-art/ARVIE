package com.example.visualchat.domain.message.controller;

import com.example.visualchat.support.JwtTestFixtures;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** [ROLE] 전용 메모리 DB에서 메시지 전송 API와 대화·정산 계약을 검증 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-chat-issue39;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
        "jwt.issuer=" + JwtTestFixtures.ISSUER, "jwt.secret=" + JwtTestFixtures.SECRET, "jwt.access-token-ttl=30m"
})
@AutoConfigureMockMvc
@Import(AiChatIntegrationContract.ClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MessageSendIntegrationTest extends AiChatIntegrationContract { }
