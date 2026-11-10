package com.example.visualchat.domain.message.entity;

import com.example.visualchat.global.config.JpaAuditingConfig;
import com.example.visualchat.domain.account_usage_window.entity.AccountUsageWindow;
import com.example.visualchat.domain.ai_request.entity.AiRequest;
import com.example.visualchat.domain.ai_request.enums.AiRequestStatus;
import com.example.visualchat.domain.chatroom.entity.ChatRoom;
import com.example.visualchat.domain.message.enums.SenderType;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.entity.UserReve;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class MessageJpaTest {

    @Autowired
    private EntityManager entityManager;

    @ParameterizedTest
    @EnumSource(SenderType.class)
    @DisplayName("두 작성자의 긴 원문과 채팅방·요청 연결, 전송·만료 시각을 저장한다")
    void persistsOriginalTextAssociationsAndTimestamps(SenderType sender) {
        Parents parents = parents("first");
        String content = "  레브와 나눈 대화 🌷\n줄바꿈과 공백도 보관해요.  ".repeat(100);
        Message message = persistMessage(parents, parents.request(), sender, content);
        Long id = message.getId();
        entityManager.clear();

        Message saved = entityManager.find(Message.class, id);

        assertThat(id).isNotNull();
        assertThat(saved.getChatRoom().getId()).isEqualTo(parents.room().getId());
        assertThat(saved.getAiRequest().getId()).isEqualTo(parents.request().getId());
        assertThat(saved.getAiRequest().getChatRoom().getId()).isEqualTo(saved.getChatRoom().getId());
        assertThat(saved.getSender()).isEqualTo(sender);
        assertThat(saved.getContent()).isEqualTo(content);
        assertThat(saved.getSentAt()).isCloseTo(message.getSentAt(), within(1, ChronoUnit.MICROS));
        assertThat(saved.getExpiresAt()).isCloseTo(message.getExpiresAt(), within(1, ChronoUnit.MICROS));
        assertThat(Duration.between(saved.getSentAt(), saved.getExpiresAt())).isEqualTo(Duration.ofDays(90));
        assertThat(entityManager.createNativeQuery("select sender from messages where id = :id")
                .setParameter("id", id).getSingleResult()).isEqualTo(sender.name());
    }

    @ParameterizedTest
    @ValueSource(shorts = {1, 180})
    @DisplayName("변경된 채팅방 보관 기간의 양 끝 경계값으로 메시지 만료 시각을 저장한다")
    void persistsExpiryAtRetentionBoundaries(short days) {
        Parents parents = parents("first");
        parents.room().modifyRetentionDays(days);
        Message message = persistMessage(parents, parents.request(), SenderType.USER, "질문");
        entityManager.clear();

        Message saved = entityManager.find(Message.class, message.getId());

        assertThat(Duration.between(saved.getSentAt(), saved.getExpiresAt())).isEqualTo(Duration.ofDays(days));
    }

    @ParameterizedTest
    @EnumSource(SenderType.class)
    @DisplayName("같은 요청에서 같은 작성자의 메시지를 중복 저장할 수 없다")
    void rejectsDuplicateSenderForSameRequest(SenderType sender) {
        Parents parents = parents("first");
        persistMessage(parents, parents.request(), sender, "첫 메시지");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> persistMessage(parents, parents.request(), sender, "두 번째 메시지"))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("uk_messages_ai_request_sender");
                });
    }

    @Test
    @DisplayName("서로 다른 요청에서는 같은 작성자의 메시지를 각각 저장할 수 있다")
    void allowsSameSenderAcrossDifferentRequests() {
        Parents parents = parents("first");
        AiRequest secondRequest = persistRequest(parents.user(), parents.room(), parents.window(), "second-key");

        Message first = persistMessage(parents, parents.request(), SenderType.USER, "첫 질문");
        Message second = persistMessage(parents, secondRequest, SenderType.USER, "다음 질문");

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(entityManager.createQuery("select count(m) from Message m", Long.class).getSingleResult()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_room_id", "ai_request_id"})
    @DisplayName("DB는 다른 채팅방의 요청과 메시지를 연결하는 변경을 거절한다")
    void rejectsMismatchedRoomInDatabase(String column) {
        Parents parents = parents("first");
        Message message = persistMessage(parents, parents.request(), SenderType.USER, "질문");
        Parents other = parents("second");
        Object otherId = "chat_room_id".equals(column) ? other.room().getId() : other.request().getId();

        // [WHY] 생성자 검증을 우회해 실제 복합 외래키가 채팅방 불일치를 차단하는지 확인한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update messages set " + column + " = :otherId where id = :id")
                        .setParameter("otherId", otherId).setParameter("id", message.getId()).executeUpdate())
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isIn("23503", "23506");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("fk_messages_ai_request_room");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_room_id", "ai_request_id", "sender", "content", "sent_at", "expires_at"})
    @DisplayName("DB는 메시지의 필수 컬럼 누락을 해당 NOT NULL 제약으로 거절한다")
    void rejectsMissingRequiredColumn(String column) {
        Parents parents = parents("first");
        Message message = persistMessage(parents, parents.request(), SenderType.USER, "질문");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update messages set " + column + " = null where id = :id")
                        .setParameter("id", message.getId()).executeUpdate())
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(column);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_room_id", "ai_request_id"})
    @DisplayName("DB는 존재하지 않는 채팅방 또는 AI 요청의 메시지 연결을 거절한다")
    void rejectsNonexistentParent(String column) {
        Parents parents = parents("first");
        Message message = persistMessage(parents, parents.request(), SenderType.USER, "질문");
        Object missingId = "chat_room_id".equals(column) ? Long.MAX_VALUE : UUID.fromString("00000000-0000-0000-0000-000000000000");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("update messages set " + column + " = :missingId where id = :id")
                        .setParameter("missingId", missingId).setParameter("id", message.getId()).executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("사용자 원문을 삭제해도 완성된 AI 답변과 요청 기록, 채팅방은 유지된다")
    void deletingUserMessageKeepsCompletedAnswerAndParents() {
        Parents parents = parents("first");
        Message source = persistMessage(parents, parents.request(), SenderType.USER, "참고 원문");
        parents.request().success(new BigDecimal("123.456789"));
        Message answer = persistMessage(parents, parents.request(), SenderType.ASSISTANT, "이미 완성된 답변");

        entityManager.remove(source);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Message.class, source.getId())).isNull();
        Message savedAnswer = entityManager.find(Message.class, answer.getId());
        assertThat(savedAnswer.getContent()).isEqualTo("이미 완성된 답변");
        assertThat(savedAnswer.getExpiresAt()).isCloseTo(answer.getExpiresAt(), within(1, ChronoUnit.MICROS));
        AiRequest savedRequest = entityManager.find(AiRequest.class, parents.request().getId());
        assertThat(savedRequest.getStatus()).isEqualTo(AiRequestStatus.SUCCEEDED);
        assertThat(savedRequest.getChargedVie()).isEqualByComparingTo("123.456789");
        assertThat(entityManager.find(ChatRoom.class, parents.room().getId())).isNotNull();
        assertThat(entityManager.find(AccountUsageWindow.class, parents.window().getId())).isNotNull();
        assertThat(entityManager.find(User.class, parents.user().getId())).isNotNull();
    }

    @Test
    @DisplayName("원문의 만료 시각에 삭제해도 아직 만료되지 않은 AI 답변은 보존된다")
    void deletingExpiredSourceAtBoundaryKeepsUnexpiredAnswer() {
        Parents parents = parents("first");
        Message source = persistMessage(parents, parents.request(), SenderType.USER, "만료될 원문");
        parents.request().success(new BigDecimal("100"));
        Message answer = persistMessage(parents, parents.request(), SenderType.ASSISTANT, "보존할 답변");
        Instant sentAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant cutoff = Instant.parse("2020-03-31T00:00:00Z");
        entityManager.createNativeQuery("update messages set sent_at = :sentAt, expires_at = :expiresAt where id = :id")
                .setParameter("sentAt", sentAt).setParameter("expiresAt", cutoff).setParameter("id", source.getId()).executeUpdate();

        // [NOTE] 만료 경계에서의 삭제 관계만 검증하며 자동 삭제 스케줄러는 후속 작업이다.
        int deleted = entityManager.createQuery("delete from Message m where m.expiresAt <= :cutoff")
                .setParameter("cutoff", cutoff).executeUpdate();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(entityManager.find(Message.class, source.getId())).isNull();
        Message savedAnswer = entityManager.find(Message.class, answer.getId());
        assertThat(savedAnswer.getExpiresAt()).isAfter(cutoff);
        assertThat(savedAnswer.getContent()).isEqualTo("보존할 답변");
        assertThat(entityManager.find(AiRequest.class, parents.request().getId()).getStatus()).isEqualTo(AiRequestStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("DB는 메시지가 참조하는 AI 요청 기록의 삭제를 거절한다")
    void rejectsDeletingReferencedRequest() {
        Parents parents = parents("first");
        persistMessage(parents, parents.request(), SenderType.USER, "질문");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("delete from ai_requests where id = :id")
                        .setParameter("id", parents.request().getId()).executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "idx_messages_chat_room_sent_at_id|chat_room_id,sent_at,id",
            "idx_messages_expires_at|expires_at"
    }, delimiter = '|')
    @DisplayName("기록 조회와 만료 정리를 위한 인덱스가 지정한 컬럼 순서로 생성된다")
    void createsHistoryAndExpiryIndexes(String indexName, String expectedColumns) {
        List<?> columns = entityManager.createNativeQuery("""
                select column_name from information_schema.index_columns
                where lower(table_name) = 'messages' and lower(index_name) = :indexName
                order by ordinal_position
                """).setParameter("indexName", indexName).getResultList();

        assertThat(columns.stream().map(column -> column.toString().toLowerCase(Locale.ROOT)).toList())
                .containsExactly(expectedColumns.split(","));
    }

    private Message persistMessage(Parents parents, AiRequest request, SenderType sender, String content) {
        Message message = Message.of(parents.room(), request, sender, content);
        entityManager.persist(message);
        entityManager.flush();
        return message;
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
        AiRequest request = persistRequest(user, room, window, "request-key");
        return new Parents(user, room, window, request);
    }

    private AiRequest persistRequest(User user, ChatRoom room, AccountUsageWindow window, String key) {
        AiRequest request = AiRequest.of(user, room, window, key, "a".repeat(64), "test-provider", "test-model",
                new BigDecimal("200"), Instant.now().plusSeconds(60));
        entityManager.persist(request);
        entityManager.flush();
        return request;
    }

    private record Parents(User user, ChatRoom room, AccountUsageWindow window, AiRequest request) {
    }
}
