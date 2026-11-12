package com.example.visualchat.domain.user.dto;

import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;

import java.time.Instant;

/** [ROLE] 회원에게 배정된 레브의 공개 정보와 배정 시각을 전달 */
public record UserReveResponse(
        Long userReveId,
        Long reveId,
        String name,
        Mbti mbti,
        String description,
        Instant assignedAt
) {
    public static UserReveResponse from(UserReve userReve) {
        Reve reve = userReve.getReve();

        // [CAUTION] 서버용 시스템 프롬프트와 추첨 가중치는 응답에 포함하지 않음
        return new UserReveResponse(
                userReve.getId(),
                reve.getId(),
                reve.getName(),
                reve.getMbti(),
                reve.getDescription(),
                userReve.getAssignedAt()
        );
    }
}