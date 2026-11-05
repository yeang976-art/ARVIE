package com.example.visualchat.domain.ai_request.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class AiRequestStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PROCESSING,PROCESSING,false",
            "PROCESSING,SUCCEEDED,true",
            "PROCESSING,FAILED,true",
            "SUCCEEDED,PROCESSING,false",
            "SUCCEEDED,SUCCEEDED,false",
            "SUCCEEDED,FAILED,false",
            "FAILED,PROCESSING,false",
            "FAILED,SUCCEEDED,false",
            "FAILED,FAILED,false"
    })
    @DisplayName("처리 중인 요청만 성공 또는 실패로 전환할 수 있다")
    void permitsOnlyDefinedTransitions(AiRequestStatus source, AiRequestStatus target, boolean expected) {
        assertThat(source.canTransitTo(target)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(AiRequestStatus.class)
    @DisplayName("어떤 상태에서도 null 상태로 전환할 수 없다")
    void rejectsNullTarget(AiRequestStatus source) {
        assertThat(source.canTransitTo(null)).isFalse();
    }
}
