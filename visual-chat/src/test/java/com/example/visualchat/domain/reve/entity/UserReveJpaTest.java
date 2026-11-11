package com.example.visualchat.domain.reve.entity;

import com.example.visualchat.global.config.JpaAuditingConfig;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserReveJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("배정 정보와 시각이 저장되며 회원과 레브는 접근할 때 지연 로딩된다")
    void persistsAssignmentAndLoadsAssociationsLazily() {
        User user = persistUser("first");
        Reve reve = persistReve("FIRST");
        UserReve assignment = UserReve.of(user, reve);
        Instant assignedAt = assignment.getAssignedAt();
        entityManager.persist(assignment);
        entityManager.flush();
        Long assignmentId = assignment.getId();
        entityManager.clear();

        UserReve savedAssignment = entityManager.find(UserReve.class, assignmentId);
        PersistenceUnitUtil persistenceUnit = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();

        assertThat(assignmentId).isNotNull();
        assertThat(savedAssignment.getAssignedAt()).isCloseTo(assignedAt, within(1, ChronoUnit.MICROS));
        assertThat(persistenceUnit.isLoaded(savedAssignment, "user")).isFalse();
        assertThat(persistenceUnit.isLoaded(savedAssignment, "reve")).isFalse();

        assertThat(savedAssignment.getUser().getNickname()).isEqualTo("user-first");
        assertThat(savedAssignment.getReve().getCode()).isEqualTo("REVE_FIRST");
        assertThat(persistenceUnit.isLoaded(savedAssignment, "user")).isTrue();
        assertThat(persistenceUnit.isLoaded(savedAssignment, "reve")).isTrue();
    }

    @Test
    @DisplayName("같은 회원에게 다른 레브를 추가 배정할 수 없다")
    void rejectsSecondAssignmentForSameUser() {
        User user = persistUser("first");
        Reve firstReve = persistReve("FIRST");
        Reve secondReve = persistReve("SECOND");
        entityManager.persist(UserReve.of(user, firstReve));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(UserReve.of(user, secondReve));
                    entityManager.flush();
                })
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("서로 다른 회원은 같은 레브를 각각 배정받을 수 있다")
    void allowsSameReveForDifferentUsers() {
        User firstUser = persistUser("first");
        User secondUser = persistUser("second");
        Reve reve = persistReve("FIRST");
        UserReve firstAssignment = UserReve.of(firstUser, reve);
        UserReve secondAssignment = UserReve.of(secondUser, reve);
        entityManager.persist(firstAssignment);
        entityManager.persist(secondAssignment);
        entityManager.flush();
        entityManager.clear();

        assertThat(firstAssignment.getId()).isNotEqualTo(secondAssignment.getId());
        assertThat(entityManager.find(UserReve.class, firstAssignment.getId()).getReve().getId())
                .isEqualTo(reve.getId());
        assertThat(entityManager.find(UserReve.class, secondAssignment.getId()).getReve().getId())
                .isEqualTo(reve.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "reve"})
    @DisplayName("필수 연관관계가 없으면 해당 속성을 이유로 저장을 거절한다")
    void rejectsMissingAssociation(String missingProperty) {
        User user = "user".equals(missingProperty) ? null : persistUser("first");
        Reve reve = "reve".equals(missingProperty) ? null : persistReve("FIRST");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(UserReve.of(user, reve));
                    entityManager.flush();
                })
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage())
                            .containsIgnoringCase(missingProperty + "_id");
                });
    }

    @Test
    @DisplayName("DB도 배정 시각이 없는 행의 저장을 거절한다")
    void rejectsMissingAssignedAtInDatabase() {
        User user = persistUser("first");
        Reve reve = persistReve("FIRST");
        entityManager.flush();

        // [WHY] 생성 시각 자동 초기화를 우회해 실제 NOT NULL 제약을 검증한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("""
                        insert into user_reves (user_id, reve_id, assigned_at)
                        values (:userId, :reveId, null)
                        """)
                        .setParameter("userId", user.getId())
                        .setParameter("reveId", reve.getId())
                        .executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("23502"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "reve"})
    @DisplayName("DB는 존재하지 않는 회원 또는 레브의 배정을 거절한다")
    void rejectsNonexistentParent(String missingParent) {
        User user = persistUser("first");
        Reve reve = persistReve("FIRST");
        entityManager.flush();
        Long userId = "user".equals(missingParent) ? Long.MAX_VALUE : user.getId();
        Long reveId = "reve".equals(missingParent) ? Long.MAX_VALUE : reve.getId();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("""
                        insert into user_reves (user_id, reve_id, assigned_at)
                        values (:userId, :reveId, current_timestamp)
                        """)
                        .setParameter("userId", userId)
                        .setParameter("reveId", reveId)
                        .executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("배정 기록을 삭제해도 회원과 레브 원본은 유지된다")
    void deletingAssignmentDoesNotDeleteParents() {
        User user = persistUser("first");
        Reve reve = persistReve("FIRST");
        UserReve assignment = UserReve.of(user, reve);
        entityManager.persist(assignment);
        entityManager.flush();
        Long assignmentId = assignment.getId();
        Long userId = user.getId();
        Long reveId = reve.getId();

        entityManager.remove(assignment);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(UserReve.class, assignmentId)).isNull();
        assertThat(entityManager.find(User.class, userId)).isNotNull();
        assertThat(entityManager.find(Reve.class, reveId)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "reve"})
    @DisplayName("DB는 배정 기록이 참조하는 회원과 레브 원본의 삭제를 거절한다")
    void rejectsDeletingReferencedParent(String parentType) {
        User user = persistUser("first");
        Reve reve = persistReve("FIRST");
        entityManager.persist(UserReve.of(user, reve));
        entityManager.flush();
        String tableName = "user".equals(parentType) ? "users" : "reves";
        Long parentId = "user".equals(parentType) ? user.getId() : reve.getId();

        // [WHY] ORM의 연관관계 선검사와 구분해 실제 DB 외래키의 삭제 제한을 확인한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("delete from " + tableName + " where id = :id")
                        .setParameter("id", parentId)
                        .executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("소유권 참조용 복합 유일 키가 실제 DB에 생성된다")
    void createsOwnershipUniqueKey() {
        List<?> columns = entityManager.createNativeQuery("""
                select column_name
                from information_schema.key_column_usage
                where lower(table_name) = 'user_reves'
                  and lower(constraint_name) = 'uk_user_reves_user_id'
                order by ordinal_position
                """).getResultList();

        assertThat(columns.stream().map(column -> column.toString().toLowerCase(Locale.ROOT)).toList())
                .containsExactly("user_id", "id");
    }

    private User persistUser(String suffix) {
        User user = User.of("user-" + suffix, suffix + "@example.com", "encoded-password");
        entityManager.persist(user);
        return user;
    }

    private Reve persistReve(String suffix) {
        Reve reve = Reve.of("REVE_" + suffix, "루미", null, "소개", "설정", BigDecimal.ONE);
        entityManager.persist(reve);
        return reve;
    }
}
