package com.example.visualchat.domain.chatroom.entity;

import com.example.visualchat.global.config.JpaAuditingConfig;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
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
class ChatRoomJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("채팅방의 소유권, 기본 보관 기간, 버전과 감사 시각을 저장한다")
    void persistsOwnershipDefaultRetentionVersionAndAuditing() {
        UserReve assignment = persistAssignment("first");
        ChatRoom room = ChatRoom.of(assignment);
        entityManager.persist(room);
        entityManager.flush();
        Long roomId = room.getId();
        Instant createdAt = room.getCreatedAt();
        Instant updatedAt = room.getUpdatedAt();
        entityManager.clear();

        ChatRoom saved = entityManager.find(ChatRoom.class, roomId);

        assertThat(roomId).isNotNull();
        assertThat(saved.getUser().getId()).isEqualTo(assignment.getUser().getId());
        assertThat(saved.getUserReve().getId()).isEqualTo(assignment.getId());
        assertThat(saved.getUserReve().getUser().getId()).isEqualTo(saved.getUser().getId());
        assertThat(saved.getRetentionDays()).isEqualTo((short) 90);
        assertThat(saved.getVersion()).isZero();
        assertThat(createdAt).isNotNull();
        assertThat(updatedAt).isNotNull();
        assertThat(saved.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(saved.getUpdatedAt()).isCloseTo(updatedAt, within(1, ChronoUnit.MICROS));
    }

    @ParameterizedTest
    @ValueSource(shorts = {1, 180})
    @DisplayName("보관 기간의 경계값을 저장하고 수정 시 버전을 증가시킨다")
    void persistsRetentionBoundaryAndIncrementsVersion(short days) {
        ChatRoom room = ChatRoom.of(persistAssignment("first"));
        entityManager.persist(room);
        entityManager.flush();
        Long previousVersion = room.getVersion();
        Instant createdAt = room.getCreatedAt();
        Instant beforeUpdate = Instant.now();

        room.modifyRetentionDays(days);
        entityManager.flush();
        Instant afterUpdate = Instant.now();
        entityManager.clear();
        ChatRoom saved = entityManager.find(ChatRoom.class, room.getId());

        assertThat(saved.getRetentionDays()).isEqualTo(days);
        assertThat(saved.getVersion()).isEqualTo(previousVersion + 1);
        assertThat(saved.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(saved.getUpdatedAt()).isBetween(beforeUpdate.minusNanos(1_000), afterUpdate.plusNanos(1_000));
    }

    @Test
    @DisplayName("하나의 배정 기록에 채팅방을 중복 생성할 수 없다")
    void rejectsDuplicateRoomForAssignment() {
        UserReve assignment = persistAssignment("first");
        entityManager.persist(ChatRoom.of(assignment));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(ChatRoom.of(assignment));
                    entityManager.flush();
                })
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
    }

    @Test
    @DisplayName("서로 다른 회원의 배정 기록에는 각각 채팅방을 생성할 수 있다")
    void allowsRoomsForDifferentAssignments() {
        ChatRoom first = ChatRoom.of(persistAssignment("first"));
        ChatRoom second = ChatRoom.of(persistAssignment("second"));
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        entityManager.clear();

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(entityManager.find(ChatRoom.class, first.getId()).getUser().getId())
                .isNotEqualTo(entityManager.find(ChatRoom.class, second.getId()).getUser().getId());
    }

    @Test
    @DisplayName("DB는 다른 회원의 배정 기록을 연결하는 채팅방 생성을 거절한다")
    void rejectsMismatchedOwnerInDatabase() {
        UserReve assignment = persistAssignment("first");
        User otherUser = persistUser("second");
        entityManager.flush();

        // [WHY] 생성자의 소유 회원 자동 설정을 우회해 실제 복합 외래키를 검증한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertRoom(otherUser.getId(), assignment.getId(), (short) 90))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isIn("23503", "23506");
                    assertThat(exception.getSQLException().getMessage())
                            .containsIgnoringCase("fk_chat_rooms_user_reve_owner");
                });
    }

    @ParameterizedTest
    @ValueSource(shorts = {0, 181})
    @DisplayName("DB도 허용 범위를 벗어난 보관 기간을 CHECK 제약으로 거절한다")
    void rejectsInvalidRetentionInDatabase(short days) {
        UserReve assignment = persistAssignment("first");
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertRoom(assignment.getUser().getId(), assignment.getId(), days))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23513");
                    assertThat(exception.getSQLException().getMessage())
                            .containsIgnoringCase("ck_chat_rooms_retention_days");
                });
    }

    @Test
    @DisplayName("DB는 보관 기간이 없는 채팅방 생성을 거절한다")
    void rejectsNullRetentionInDatabase() {
        UserReve assignment = persistAssignment("first");
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertRoom(assignment.getUser().getId(), assignment.getId(), null))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("retention_days");
                });
    }

    @Test
    @DisplayName("오래된 버전의 보관 기간 변경은 낙관적 잠금으로 거절한다")
    void rejectsUpdateFromStaleVersion() {
        ChatRoom room = ChatRoom.of(persistAssignment("first"));
        entityManager.persist(room);
        entityManager.flush();
        Long roomId = room.getId();
        entityManager.clear();

        // [WHY] 같은 버전을 읽은 두 작업을 재현하고 먼저 저장된 설정의 덮어쓰기를 막는지 확인한다.
        ChatRoom stale = entityManager.find(ChatRoom.class, roomId);
        entityManager.detach(stale);
        ChatRoom current = entityManager.find(ChatRoom.class, roomId);
        current.modifyRetentionDays((short) 30);
        entityManager.flush();
        entityManager.clear();

        stale.modifyRetentionDays((short) 180);

        assertThatExceptionOfType(OptimisticLockException.class)
                .isThrownBy(() -> {
                    entityManager.merge(stale);
                    entityManager.flush();
                });
    }

    @Test
    @DisplayName("채팅방을 삭제해도 배정 기록과 회원, 레브는 유지된다")
    void deletingRoomDoesNotDeleteParents() {
        UserReve assignment = persistAssignment("first");
        ChatRoom room = ChatRoom.of(assignment);
        entityManager.persist(room);
        entityManager.flush();

        entityManager.remove(room);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(ChatRoom.class, room.getId())).isNull();
        assertThat(entityManager.find(UserReve.class, assignment.getId())).isNotNull();
        assertThat(entityManager.find(User.class, assignment.getUser().getId())).isNotNull();
        assertThat(entityManager.find(Reve.class, assignment.getReve().getId())).isNotNull();
    }

    @Test
    @DisplayName("DB는 채팅방이 참조하는 배정 기록의 삭제를 거절한다")
    void rejectsDeletingReferencedAssignment() {
        UserReve assignment = persistAssignment("first");
        entityManager.persist(ChatRoom.of(assignment));
        entityManager.flush();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("delete from user_reves where id = :id")
                        .setParameter("id", assignment.getId())
                        .executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("AI 요청의 소유권 참조에 필요한 복합 유일 키를 DB에 생성한다")
    void createsOwnershipUniqueKey() {
        List<?> columns = entityManager.createNativeQuery("""
                select column_name
                from information_schema.key_column_usage
                where lower(table_name) = 'chat_rooms'
                  and lower(constraint_name) = 'uk_chat_rooms_user_id'
                order by ordinal_position
                """).getResultList();

        assertThat(columns.stream().map(column -> column.toString().toLowerCase(Locale.ROOT)).toList())
                .containsExactly("user_id", "id");
    }

    private void insertRoom(Long userId, Long assignmentId, Short retentionDays) {
        entityManager.createNativeQuery("""
                insert into chat_rooms (user_id, user_reve_id, retention_days, version, created_at, updated_at)
                values (:userId, :assignmentId, :retentionDays, 0, current_timestamp, current_timestamp)
                """)
                .setParameter("userId", userId)
                .setParameter("assignmentId", assignmentId)
                .setParameter("retentionDays", retentionDays)
                .executeUpdate();
    }

    private UserReve persistAssignment(String suffix) {
        User user = persistUser(suffix);
        Reve reve = Reve.of("REVE_" + suffix, "루미", "소개", "설정", BigDecimal.ONE);
        entityManager.persist(reve);
        UserReve assignment = UserReve.of(user, reve);
        entityManager.persist(assignment);
        return assignment;
    }

    private User persistUser(String suffix) {
        User user = User.of("user-" + suffix, suffix + "@example.com", "encoded-password");
        entityManager.persist(user);
        return user;
    }
}
