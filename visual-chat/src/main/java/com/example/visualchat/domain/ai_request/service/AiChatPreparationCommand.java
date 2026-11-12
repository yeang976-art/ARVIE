package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.metering.TerraVieCalculator;
import com.example.visualchat.domain.ai_request.model.AiChatMessage;
import com.example.visualchat.domain.ai_request.model.AiChatRequest;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.chatroom.service.ChatRoomService;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** [ROLE] 본인 채팅방의 레브 설정과 유효한 대화 맥락으로 AI 입력을 구성 */
@Service
@RequiredArgsConstructor
public class AiChatPreparationCommand {

    // [NOTE] 문자열 길이는 요청 DTO의 @Size와 동일한 기준으로 검사
    private static final int MAX_MESSAGE_LENGTH = 2_000;

    // [NOTE] AI에 요청할 최대 출력 토큰 수이며 실제 차감은 보고된 사용량으로 계산
    private static final long MAX_OUTPUT_TOKENS = 1_024L;

    private final Clock clock;
    private final MessageService messageService;
    private final ChatRoomService chatRoomService;

    // [TOPIC] 소유권 확인과 AI 입력 구성
    @Transactional(readOnly = true)
    public AiChatRequest prepare(Long userId, Long roomId, String content) {
        validateContent(content);

        ChatRoom room = chatRoomService.getOwnedRoom(userId, roomId);

        // [WHY] 지연 로딩되는 레브 설정은 트랜잭션 안에서 읽어 문자열로 복사
        String systemPrompt = room.getUserReve()
                .getReve()
                .getSystemPrompt();

        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new ServiceException(ErrorCode.SERVER_ERROR, "레브 대화 설정을 확인할 수 없습니다.");
        }

        // [WHY] 이번 맥락 조회에 동일한 만료 판정 시각을 사용
        Instant readAt = clock.instant();

        List<AiChatMessage> messages = new ArrayList<>();

        messageService.findRecentContext(userId, roomId, readAt)
                .forEach(message -> messages.add(
                        new AiChatMessage(message.sender(), message.content())
                ));

        // [WHY] 과거 맥락 조회와 분리해 이번 사용자 입력을 정확히 한 번 추가
        messages.add(new AiChatMessage(SenderType.USER, content));

        // [CAUTION] 엔티티를 반환하지 않아 외부 AI 호출 중 지연 로딩이 발생하지 않음
        return new AiChatRequest(TerraVieCalculator.MODEL_NAME, systemPrompt, messages, MAX_OUTPUT_TOKENS);
    }

    // [TOPIC] 내부 호출에도 적용할 사용자 메시지 검증
    private void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_MESSAGE_LENGTH) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "메시지는 공백을 제외한 내용을 포함해 1~2,000자로 입력해 주세요.");
        }
    }
}