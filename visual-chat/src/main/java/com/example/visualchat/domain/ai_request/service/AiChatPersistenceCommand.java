package com.example.visualchat.domain.ai_request.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.ai_request.model.AiChatResult;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** [ROLE] 사용자 메시지 저장과 AI 답변·요청 완료·VIE 정산의 원자적 반영을 조율 */
@Service
@RequiredArgsConstructor
public class AiChatPersistenceCommand {

    private final MessageService messageService;
    private final AiRequestService aiRequestService;
    private final AiRequestCommand aiRequestCommand;

    // [TOPIC] 신규 접수된 요청의 사용자 메시지 저장
    @Transactional
    public void saveUserMessage(Long userId, UUID requestId, String content) {
        AiRequest request = aiRequestService.getOwnedRequest(userId, requestId);

        if (request.getStatus() != AiRequestStatus.PROCESSING) {
            throw new ServiceException(ErrorCode.INVALID_AI_REQUEST_STATUS);
        }

        messageService.create(request.getChatRoom(), request, SenderType.USER, content);
    }

    // [TOPIC] AI 답변 저장과 성공 정산을 하나의 트랜잭션으로 반영
    @Transactional
    public void completeSuccess(Long userId, UUID requestId, AiChatResult result) {
        validateResult(result);

        AiRequest request = aiRequestService.getOwnedRequest(userId, requestId);

        request.recordProviderResponse(result.providerRequestId());

        // [WHY] 기존 정산 로직도 현재 트랜잭션에 참여시켜 함께 커밋·롤백
        aiRequestCommand.completeSuccess(userId, requestId, result.usage());

        // [CAUTION] 답변 저장에 실패하면 앞서 변경한 요청 상태와 정산도 롤백
        messageService.create(request.getChatRoom(), request, SenderType.ASSISTANT, result.content());
    }

    private void validateResult(AiChatResult result) {
        if (result == null || result.usage() == null || result.content() == null || result.content().isBlank()) {
            throw new ServiceException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
    }
}