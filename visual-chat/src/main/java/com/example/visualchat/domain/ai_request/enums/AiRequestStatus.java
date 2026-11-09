package com.example.visualchat.domain.ai_request.enums;

/** [ROLE] AI 요청의 처리 상태와 허용되는 상태 전이를 정의 */
public enum AiRequestStatus {
    PROCESSING {
        @Override
        public boolean canTransitTo(AiRequestStatus target) {
            return target == SUCCEEDED || target == FAILED;
        }
    },
    SUCCEEDED {
        @Override
        public boolean canTransitTo(AiRequestStatus target) {
            return false;
        }
    },
    FAILED {
        @Override
        public boolean canTransitTo(AiRequestStatus target) {
            return false;
        }
    };

    public abstract boolean canTransitTo(AiRequestStatus target);
}
