package com.example.visualchat.domain.user.repository;

import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.reve.enums.Mbti;
import com.example.visualchat.domain.user.dto.UserReveResponse;
import com.example.visualchat.domain.user.entity.User;
import com.example.visualchat.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** [ROLE] 회원별 배정 조회의 소유권 조건과 연관 레브 조회 쿼리를 검증 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class UserReveRepositoryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserReveRepository repository;
    private Long ownerId;
    private Long unassignedMemberId;
    private Long assignmentId;

    @BeforeEach
    void prepareAssignment() {
        User owner = User.of("owner", "owner@example.com", "encoded-test-value");
        User unassigned = User.of("unassigned", "unassigned@example.com", "encoded-test-value");
        Reve reve = Reve.of("REVE_QUERY", "루미", Mbti.INFP, "레브 소개", "서버 전용 설정", BigDecimal.ONE);
        entityManager.persist(owner);
        entityManager.persist(unassigned);
        entityManager.persist(reve);
        UserReve assignment = UserReve.of(owner, reve);
        entityManager.persist(assignment);
        entityManager.flush();
        ownerId = owner.getId();
        unassignedMemberId = unassigned.getId();
        assignmentId = assignment.getId();
        // [WHY] 이미 로딩된 객체 때문에 추가 조회가 가려지지 않도록 영속성 컨텍스트를 비움
        entityManager.clear();
    }

    @Test
    @DisplayName("배정과 레브를 한 번에 조회해 DTO로 변환할 때 추가 SQL이 발생하지 않는다")
    void fetchesAssignmentAndReveInOneQuery() {
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        UserReve assignment = repository.findByUser_Id(ownerId).orElseThrow();
        var response = UserReveResponse.from(assignment);

        assertThat(response.userReveId()).isEqualTo(assignmentId);
        assertThat(response.name()).isEqualTo("루미");
        assertThat(response.description()).isEqualTo("레브 소개");
        assertThat(response.mbti()).isEqualTo(Mbti.INFP);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원에게 배정이 있어도 본인 배정이 없으면 빈 결과를 반환한다")
    void doesNotReturnAnotherMembersAssignment() {
        assertThat(repository.findByUser_Id(unassignedMemberId)).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 회원 ID로 조회하면 빈 결과를 반환한다")
    void returnsEmptyForMissingMember() {
        assertThat(repository.findByUser_Id(Long.MAX_VALUE)).isEmpty();
    }
}
