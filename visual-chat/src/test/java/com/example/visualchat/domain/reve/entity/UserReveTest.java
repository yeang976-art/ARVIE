package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.domain.reve.enums.Mbti;

import com.example.visualchat.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserReveTest {

    @Test
    @DisplayName("회원과 레브를 연결하고 배정 시각을 생성 시점으로 초기화한다")
    void createsAssignmentWithAssignedAt() {
        User user = User.of("arvie", "user@example.com", "encoded-password");
        Reve reve = Reve.of("REVE_001", "루미", Mbti.INFP, "소개", "설정", BigDecimal.ONE);
        Instant beforeAssignment = Instant.now();

        UserReve assignment = UserReve.of(user, reve);

        Instant afterAssignment = Instant.now();
        assertThat(assignment.getId()).isNull();
        assertThat(assignment.getUser()).isSameAs(user);
        assertThat(assignment.getReve()).isSameAs(reve);
        assertThat(assignment.getAssignedAt()).isBetween(beforeAssignment, afterAssignment);
    }
}
