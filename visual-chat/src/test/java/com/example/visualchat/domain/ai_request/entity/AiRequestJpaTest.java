package com.example.visualchat.domain.ai_request.entity;

import com.example.visualchat.global.config.JpaAuditingConfig;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class AiRequestJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("UUID, 소유권 연결과 처리 중 상태를 저장하고 집계 전 필드는 null로 유지한다")
    void persistsUuidOwnershipAndInitialState() {
        Parents parents = parents("first");
        AiRequest request = persistRequest(parents, "request-key");
        UUID id = request.getId();
        entityManager.clear();

        AiRequest saved = entityManager.find(AiRequest.class, id);

        assertThat(id).isNotNull();
        assertThat(saved.getUser().getId()).isEqualTo(parents.user().getId());
        assertThat(saved.getChatRoom().getId()).isEqualTo(parents.room().getId());
        assertThat(saved.getAccountUsageWindow().getId()).isEqualTo(parents.window().getId());
        assertThat(saved.getUsageWindowId()).isEqualTo(parents.window().getId());
        assertThat(saved.getIdempotencyKey()).isEqualTo("request-key");
        assertThat(saved.getRequestFingerprint()).isEqualTo("a".repeat(64));
        assertThat(saved.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(saved.getProvider()).isEqualTo("test-provider");
        assertThat(saved.getModelName()).isEqualTo("test-model");
        assertThat(saved.getReservedVie()).isEqualByComparingTo("200");
        assertThat(saved.getChargedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getStartedAt()).isEqualTo(request.getStartedAt());
        assertThat(saved.getProcessingDeadlineAt()).isEqualTo(request.getProcessingDeadlineAt());
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getFinishedAt()).isNull();
        assertThat(saved.getErrorCode()).isNull();
        assertThat(saved.getProviderRequestId()).isNull();
        assertThat(saved.getInputTokens()).isNull();
        assertThat(saved.getOutputTokens()).isNull();
        assertThat(saved.getMeteringVersion()).isNull();
        assertThat(entityManager.createNativeQuery("select status from ai_requests where id = :id")
                .setParameter("id", id).getSingleResult()).isEqualTo("PROCESSING");
    }

    @ParameterizedTest
    @EnumSource(value = AiRequestStatus.class, names = {"SUCCEEDED", "FAILED"})
    @DisplayName("성공과 실패 결과, 정산량, 종료 시각을 저장하고 버전을 증가시킨다")
    void persistsCompletionAndIncrementsVersion(AiRequestStatus target) {
        AiRequest request = persistRequest(parents("first"), "request-key");
        Long version = request.getVersion();

        if (target == AiRequestStatus.SUCCEEDED) {
            request.success(new BigDecimal("123.456789"));
        } else {
            request.fail(ErrorCode.SERVER_ERROR);
        }
        entityManager.flush();
        entityManager.clear();
        AiRequest saved = entityManager.find(AiRequest.class, request.getId());

        assertThat(saved.getStatus()).isEqualTo(target);
        assertThat(saved.getReservedVie()).isEqualByComparingTo("200");
        assertThat(saved.getChargedVie()).isEqualByComparingTo(target == AiRequestStatus.SUCCEEDED ? "123.456789" : "0");
        assertThat(saved.getFinishedAt()).isEqualTo(request.getFinishedAt());
        assertThat(saved.getErrorCode()).isEqualTo(target == AiRequestStatus.FAILED ? ErrorCode.SERVER_ERROR.getCode() : null);
        assertThat(saved.getVersion()).isEqualTo(version + 1);
    }

    @Test
    @DisplayName("같은 회원의 멱등키는 요청 UUID가 달라도 중복 저장할 수 없다")
    void rejectsDuplicateIdempotencyKeyForSameUser() {
        Parents parents = parents("first");
        persistRequest(parents, "same-key");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> persistRequest(parents, "same-key"))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("uk_ai_requests_user_idempotency_key");
                });
    }

    @Test
    @DisplayName("다른 회원은 같은 멱등키를 사용할 수 있다")
    void allowsSameKeyForDifferentUsers() {
        AiRequest first = persistRequest(parents("first"), "same-key");
        AiRequest second = persistRequest(parents("second"), "same-key");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(entityManager.createQuery("select count(r) from AiRequest r", Long.class).getSingleResult()).isEqualTo(2L);
    }

    @Test
    @DisplayName("같은 채팅방에 서로 다른 멱등키의 요청을 여러 건 저장할 수 있다")
    void allowsMultipleRequestsInSameRoom() {
        Parents parents = parents("first");
        AiRequest first = persistRequest(parents, "first-key");
        AiRequest second = persistRequest(parents, "second-key");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getChatRoom().getId()).isEqualTo(second.getChatRoom().getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_room_id", "usage_window_id"})
    @DisplayName("DB는 다른 회원의 채팅방 또는 이용 주기를 연결하는 변경을 거절한다")
    void rejectsMismatchedOwnerInDatabase(String column) {
        AiRequest request = persistRequest(parents("first"), "request-key");
        Parents other = parents("second");
        Long otherId = "chat_room_id".equals(column) ? other.room().getId() : other.window().getId();
        String constraint = "chat_room_id".equals(column) ? "fk_ai_requests_chat_room_owner" : "fk_ai_requests_usage_window_owner";

        // [WHY] 생성자의 소유권 검증을 우회해 두 복합 외래키가 실제로 작동하는지 확인한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update ai_requests set " + column + " = :parentId where id = :id")
                        .setParameter("parentId", otherId).setParameter("id", request.getId()).executeUpdate())
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isIn("23503", "23506");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(constraint);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserved_vie", "charged_vie"})
    @DisplayName("DB는 예약량과 차감량의 음수를 CHECK 제약으로 거절한다")
    void rejectsNegativeVieInDatabase(String column) {
        AiRequest request = persistRequest(parents("first"), "request-key");

        assertCheckViolation("ck_ai_requests_vie_nonnegative", () -> entityManager.createNativeQuery(
                        "update ai_requests set status = 'SUCCEEDED', finished_at = started_at, " + column + " = -0.000001 where id = :id")
                .setParameter("id", request.getId()).executeUpdate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"input_tokens", "output_tokens"})
    @DisplayName("DB는 기록된 입력·출력 토큰 수의 음수를 거절한다")
    void rejectsNegativeTokensInDatabase(String column) {
        AiRequest request = persistRequest(parents("first"), "request-key");

        assertCheckViolation("ck_ai_requests_tokens_nonnegative", () -> entityManager.createNativeQuery(
                        "update ai_requests set " + column + " = -1 where id = :id")
                .setParameter("id", request.getId()).executeUpdate());
    }

    @ParameterizedTest
    @CsvSource({"PROCESSING,false,0.000001", "FAILED,true,0.000001", "PROCESSING,true,0", "SUCCEEDED,false,0", "FAILED,false,0"})
    @DisplayName("DB는 상태와 차감량 또는 종료 시각이 맞지 않는 조합을 거절한다")
    void rejectsInvalidStateAndSettlementCombination(String status, boolean finished, String charge) {
        AiRequest request = persistRequest(parents("first"), "request-key");
        String finishedSql = finished ? "started_at" : "null";
        String constraint = new BigDecimal(charge).signum() > 0 ? "ck_ai_requests_charge_status" : "ck_ai_requests_completion";

        assertCheckViolation(constraint, () -> entityManager.createNativeQuery(
                        "update ai_requests set status = :status, charged_vie = :charge, finished_at = " + finishedSql + " where id = :id")
                .setParameter("status", status).setParameter("charge", new BigDecimal(charge))
                .setParameter("id", request.getId()).executeUpdate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQUAL_DEADLINE", "PAST_DEADLINE", "PAST_FINISH"})
    @DisplayName("DB는 시작 이전 또는 같은 처리 기한과 시작 이전 종료 시각을 거절한다")
    void rejectsInvalidTimeOrder(String scenario) {
        AiRequest request = persistRequest(parents("first"), "request-key");
        String sql = switch (scenario) {
            case "EQUAL_DEADLINE" -> "update ai_requests set processing_deadline_at = started_at where id = :id";
            case "PAST_DEADLINE" -> "update ai_requests set processing_deadline_at = :past where id = :id";
            default -> "update ai_requests set status = 'SUCCEEDED', finished_at = :past where id = :id";
        };

        assertCheckViolation("ck_ai_requests_time_order", () -> {
            var query = entityManager.createNativeQuery(sql).setParameter("id", request.getId());
            if (!"EQUAL_DEADLINE".equals(scenario)) {
                query.setParameter("past", request.getStartedAt().minusSeconds(1));
            }
            query.executeUpdate();
        });
    }

    @Test
    @DisplayName("처리 기한 경과만으로 실패하지 않으며 기한 이후에도 확인된 성공을 저장할 수 있다")
    void allowsConfirmedSuccessAfterDeadlineWithoutAutomaticFailure() {
        AiRequest request = persistRequest(parents("first"), "request-key");
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        entityManager.createNativeQuery("update ai_requests set started_at = :start, processing_deadline_at = :deadline where id = :id")
                .setParameter("start", past).setParameter("deadline", past.plusSeconds(1))
                .setParameter("id", request.getId()).executeUpdate();
        entityManager.clear();
        AiRequest expired = entityManager.find(AiRequest.class, request.getId());

        assertThat(expired.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        expired.success(new BigDecimal("10.123456"));
        entityManager.flush();
        entityManager.clear();

        AiRequest saved = entityManager.find(AiRequest.class, request.getId());
        assertThat(saved.getStatus()).isEqualTo(AiRequestStatus.SUCCEEDED);
        assertThat(saved.getFinishedAt()).isAfter(saved.getProcessingDeadlineAt());
    }

    @Test
    @DisplayName("수량 최댓값과 토큰 0, 제공사 요청 ID 및 환산 버전을 정확히 저장한다")
    void persistsQuantityBoundariesAndMeteringMetadata() {
        Parents parents = parents("first");
        BigDecimal maximum = new BigDecimal("999999999999.999999");
        AiRequest request = create(parents, "k".repeat(128), maximum);
        entityManager.persist(request);
        request.success(maximum);
        entityManager.flush();
        entityManager.createNativeQuery("""
                update ai_requests
                set input_tokens = 0, output_tokens = :outputTokens,
                    provider_request_id = :providerRequestId, metering_version = :meteringVersion
                where id = :id
                """)
                .setParameter("outputTokens", Long.MAX_VALUE)
                .setParameter("providerRequestId", "p".repeat(255))
                .setParameter("meteringVersion", "v".repeat(64))
                .setParameter("id", request.getId()).executeUpdate();
        entityManager.clear();

        AiRequest saved = entityManager.find(AiRequest.class, request.getId());
        assertThat(saved.getReservedVie()).isEqualByComparingTo(maximum);
        assertThat(saved.getChargedVie()).isEqualByComparingTo(maximum);
        assertThat(saved.getInputTokens()).isZero();
        assertThat(saved.getOutputTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(saved.getProviderRequestId()).hasSize(255);
        assertThat(saved.getMeteringVersion()).hasSize(64);
        assertThat(saved.getIdempotencyKey()).hasSize(128);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "chat_room_id", "usage_window_id", "idempotency_key", "request_fingerprint", "status",
            "provider", "model_name", "reserved_vie", "charged_vie", "started_at", "processing_deadline_at", "version"})
    @DisplayName("DB는 요청의 필수 컬럼 누락을 해당 NOT NULL 제약으로 거절한다")
    void rejectsMissingRequiredColumn(String column) {
        AiRequest request = persistRequest(parents("first"), "request-key");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update ai_requests set " + column + " = null where id = :id")
                        .setParameter("id", request.getId()).executeUpdate())
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(column);
                });
    }

    @ParameterizedTest
    @CsvSource({"idempotency_key,129", "request_fingerprint,65", "provider,65", "model_name,129",
            "provider_request_id,256", "metering_version,65", "error_code,65"})
    @DisplayName("DB는 문자열 컬럼의 최대 길이를 넘는 저장을 거절한다")
    void rejectsExcessiveColumnLength(String column, int length) {
        AiRequest request = persistRequest(parents("first"), "request-key");

        assertThatExceptionOfType(DataException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update ai_requests set " + column + " = :value where id = :id")
                        .setParameter("value", "x".repeat(length)).setParameter("id", request.getId()).executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("22001"));
    }

    @Test
    @DisplayName("먼저 저장된 완료 결과를 오래된 버전의 다른 완료 결과로 덮어쓸 수 없다")
    void rejectsStaleCompletion() {
        AiRequest request = persistRequest(parents("first"), "request-key");
        entityManager.clear();
        AiRequest stale = entityManager.find(AiRequest.class, request.getId());
        entityManager.detach(stale);
        AiRequest current = entityManager.find(AiRequest.class, request.getId());
        current.success(new BigDecimal("123.456789"));
        entityManager.flush();
        entityManager.clear();
        stale.fail(ErrorCode.SERVER_ERROR);

        // [NOTE] 엔티티 버전 충돌 검증이며 정산 트랜잭션의 원자성과 재시도는 후속 작업이다.
        assertThatExceptionOfType(OptimisticLockException.class)
                .isThrownBy(() -> {
                    entityManager.merge(stale);
                    entityManager.flush();
                });
    }

    @Test
    @DisplayName("요청 삭제 시 회원, 채팅방과 이용 주기를 함께 삭제하지 않는다")
    void deletingRequestKeepsParents() {
        Parents parents = parents("first");
        AiRequest request = persistRequest(parents, "request-key");

        entityManager.remove(request);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(AiRequest.class, request.getId())).isNull();
        assertThat(entityManager.find(User.class, parents.user().getId())).isNotNull();
        assertThat(entityManager.find(ChatRoom.class, parents.room().getId())).isNotNull();
        assertThat(entityManager.find(AccountUsageWindow.class, parents.window().getId())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_rooms", "account_usage_windows"})
    @DisplayName("DB는 요청이 참조하는 채팅방과 이용 주기의 삭제를 거절한다")
    void rejectsDeletingReferencedParent(String table) {
        Parents parents = parents("first");
        persistRequest(parents, "request-key");
        Long id = "chat_rooms".equals(table) ? parents.room().getId() : parents.window().getId();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("delete from " + table + " where id = :id")
                        .setParameter("id", id).executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("메시지의 채팅방·요청 연결에 필요한 복합 유일 키를 DB에 생성한다")
    void createsMessageOwnershipUniqueKey() {
        List<?> columns = entityManager.createNativeQuery("""
                select column_name from information_schema.key_column_usage
                where lower(table_name) = 'ai_requests'
                  and lower(constraint_name) = 'uk_ai_requests_chat_room_id'
                order by ordinal_position
                """).getResultList();

        assertThat(columns.stream().map(column -> column.toString().toLowerCase(Locale.ROOT)).toList())
                .containsExactly("chat_room_id", "id");
    }

    private void assertCheckViolation(String name, Runnable operation) {
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(operation::run)
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23513");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(name);
                });
    }

    private AiRequest persistRequest(Parents parents, String key) {
        AiRequest request = create(parents, key, new BigDecimal("200"));
        entityManager.persist(request);
        entityManager.flush();
        return request;
    }

    private AiRequest create(Parents parents, String key, BigDecimal reservation) {
        return AiRequest.of(parents.user(), parents.room(), parents.window(), key, "a".repeat(64),
                "test-provider", "test-model", reservation, Instant.now().plusSeconds(60));
    }

    private Parents parents(String suffix) {
        User user = User.of("user-" + suffix, suffix + "@example.com", "encoded-password");
        entityManager.persist(user);
        Reve reve = Reve.of("REVE_" + suffix, "루미", "소개", "설정", BigDecimal.ONE);
        entityManager.persist(reve);
        UserReve assignment = UserReve.of(user, reve);
        entityManager.persist(assignment);
        ChatRoom room = ChatRoom.of(assignment);
        entityManager.persist(room);
        AccountUsageWindow window = AccountUsageWindow.of(user);
        entityManager.persist(window);
        entityManager.flush();
        return new Parents(user, room, window);
    }

    private record Parents(User user, ChatRoom room, AccountUsageWindow window) {
    }
}
