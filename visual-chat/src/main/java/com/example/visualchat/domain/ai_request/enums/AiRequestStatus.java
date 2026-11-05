package com.example.visualchat.domain.ai_request.enums;

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
