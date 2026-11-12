package com.example.visualchat.domain.ai_request.port;

import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.ai_request.model.AiChatResult;

/** [ROLE] AI 제공자와의 대화 요청·응답 규격을 정의 */
public interface AiChatClient {

    // [CAUTION] 외부 호출이므로 DB 트랜잭션 밖에서 실행
    AiChatResult generate(AiChatRequest request);
}