package com.example.visualchat.domain.account_usage_window.entity;

import com.example.visualchat.common.config.JpaAuditingConfig;
import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class AccountUsageWindowJpaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("초기 주기와 제공량, 버전, 감사 시각을 저장하고 회원을 지연 로딩한다")
    void persistsInitialWindowAndLoadsOwnerLazily() {
        User user = persistUser("first");
        AccountUsageWindow window = AccountUsageWindow.of(user);
        entityManager.persist(window);
        entityManager.flush();
        Long id = window.getId();
        Instant createdAt = window.getCreatedAt();
        Instant updatedAt = window.getUpdatedAt();
        entityManager.clear();

        AccountUsageWindow saved = entityManager.find(AccountUsageWindow.class, id);

        assertThat(id).isNotNull();
        assertThat(saved.getPeriodStartAt()).isCloseTo(user.getSignupCompletedAt(), within(1, ChronoUnit.MICROS));
        assertThat(Duration.between(saved.getPeriodStartAt(), saved.getPeriodEndAt())).isEqualTo(Duration.ofDays(30));
        assertThat(saved.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(saved.getUsedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getReservedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getRemainingVie()).isEqualByComparingTo("20000");
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(saved.getUpdatedAt()).isCloseTo(updatedAt, within(1, ChronoUnit.MICROS));
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(saved, "user")).isFalse();
        assertThat(saved.getUser().getNickname()).isEqualTo("user-first");
    }

    @Test
    @DisplayName("소수 예약량을 정확히 저장하고 수정 시 버전과 수정 시각을 갱신한다")
    void persistsReservationAndIncrementsVersion() {
        AccountUsageWindow window = persistWindow("first");
        Instant createdAt = window.getCreatedAt();
        Long version = window.getVersion();
        Instant beforeUpdate = Instant.now();

        window.reserveVie(new BigDecimal("200.123456"));
        entityManager.flush();
        Instant afterUpdate = Instant.now();
        entityManager.clear();
        AccountUsageWindow saved = entityManager.find(AccountUsageWindow.class, window.getId());

        assertThat(saved.getAllowanceVie()).isEqualByComparingTo("20000");
        assertThat(saved.getUsedVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getReservedVie()).isEqualByComparingTo("200.123456");
        assertThat(saved.getRemainingVie()).isEqualByComparingTo("19799.876544");
        assertThat(saved.getVersion()).isEqualTo(version + 1);
        assertThat(saved.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
        assertThat(saved.getUpdatedAt()).isBetween(beforeUpdate.minusNanos(1_000), afterUpdate.plusNanos(1_000));
    }

    @Test
    @DisplayName("같은 회원과 시작 시각으로 이용 주기를 중복 저장할 수 없다")
    void rejectsDuplicatePeriodForSameUser() {
        AccountUsageWindow window = persistWindow("first");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> {
                    entityManager.persist(AccountUsageWindow.of(window.getUser()));
                    entityManager.flush();
                })
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getSQLException().getMessage())
                            .containsIgnoringCase("uk_account_usage_windows_user_period_start");
                });
    }

    @Test
    @DisplayName("같은 회원이라도 시작 시각이 다른 이용 주기 기록을 저장할 수 있다")
    void allowsDifferentPeriodsForSameUser() {
        AccountUsageWindow first = persistWindow("first");
        Instant start = first.getPeriodEndAt();

        insertWindow(first.getUser().getId(), start, start.plus(30, ChronoUnit.DAYS),
                new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(entityManager.createQuery("select count(w) from AccountUsageWindow w where w.user.id = :userId", Long.class)
                .setParameter("userId", first.getUser().getId()).getSingleResult()).isEqualTo(2L);
    }

    @Test
    @DisplayName("서로 다른 회원은 같은 시작 시각의 이용 주기를 가질 수 있다")
    void allowsSameStartForDifferentUsers() {
        AccountUsageWindow first = persistWindow("first");
        User secondUser = persistUser("second");
        entityManager.flush();

        insertWindow(secondUser.getId(), first.getPeriodStartAt(), first.getPeriodEndAt(),
                new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(entityManager.createQuery("select count(w) from AccountUsageWindow w", Long.class)
                .getSingleResult()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("DB는 종료 시각이 시작 시각과 같거나 앞선 주기를 거절한다")
    void rejectsInvalidPeriodOrder(long durationDays) {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertWindow(user.getId(), start, start.plus(durationDays, ChronoUnit.DAYS),
                        new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23513");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("ck_account_usage_windows_period_order");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "0,0,0,0",
            "20000,19999.999999,0.000001,0",
            "20000,0.1,0.2,19999.7",
            "999999999999.999999,0,999999999999.999999,0"
    })
    @DisplayName("DB는 0, 제공량과 같은 사용·예약 합계, numeric 경계값을 정확히 저장한다")
    void acceptsQuantityBoundariesAndCalculatesRemaining(String allowance, String used, String reserved, String remaining) {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        insertWindow(user.getId(), start, start.plus(30, ChronoUnit.DAYS),
                new BigDecimal(allowance), new BigDecimal(used), new BigDecimal(reserved));
        entityManager.clear();
        AccountUsageWindow saved = findWindow(user.getId());

        assertThat(saved.getAllowanceVie()).isEqualByComparingTo(allowance);
        assertThat(saved.getUsedVie()).isEqualByComparingTo(used);
        assertThat(saved.getReservedVie()).isEqualByComparingTo(reserved);
        assertThat(saved.getRemainingVie()).isEqualByComparingTo(remaining);
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0", "20000,-0.000001,0", "20000,0,-0.000001"})
    @DisplayName("DB는 제공량, 확정 사용량, 활성 예약량의 음수를 거절한다")
    void rejectsNegativeBalances(String allowance, String used, String reserved) {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        // [WHY] 엔티티 메서드의 검증을 우회해 DB가 음수 수량을 직접 차단하는지 확인한다.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertWindow(user.getId(), start, start.plus(30, ChronoUnit.DAYS),
                        new BigDecimal(allowance), new BigDecimal(used), new BigDecimal(reserved)))
                .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("23513"));
    }

    @Test
    @DisplayName("DB는 확정 사용량과 예약량의 합이 제공량을 최소 단위만큼 넘는 경우도 거절한다")
    void rejectsUsageAndReservationAboveAllowance() {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertWindow(user.getId(), start, start.plus(30, ChronoUnit.DAYS),
                        new BigDecimal("20000"), new BigDecimal("19999.999999"), new BigDecimal("0.000002")))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23513");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase("ck_account_usage_windows_vie_limit");
                });
    }

    @Test
    @DisplayName("이미 확정된 사용량을 제외한 잔여 VIE까지만 추가 예약할 수 있다")
    void reservesOnlyBalanceRemainingAfterConfirmedUsage() {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();
        insertWindow(user.getId(), start, start.plus(30, ChronoUnit.DAYS),
                new BigDecimal("20000"), new BigDecimal("19999.9"), BigDecimal.ZERO);
        entityManager.clear();
        AccountUsageWindow window = findWindow(user.getId());

        window.reserveVie(new BigDecimal("0.1"));
        entityManager.flush();

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(() -> window.reserveVie(new BigDecimal("0.000001")))
                .satisfies(exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(window.getRemainingVie()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(window.getUsedVie()).isEqualByComparingTo("19999.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "period_start_at", "period_end_at", "allowance_vie", "used_vie", "reserved_vie"})
    @DisplayName("DB는 이용 주기의 필수 컬럼 누락을 거절한다")
    void rejectsMissingRequiredColumn(String missingColumn) {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertWindow(
                        "user_id".equals(missingColumn) ? null : user.getId(),
                        "period_start_at".equals(missingColumn) ? null : start,
                        "period_end_at".equals(missingColumn) ? null : start.plus(30, ChronoUnit.DAYS),
                        "allowance_vie".equals(missingColumn) ? null : new BigDecimal("20000"),
                        "used_vie".equals(missingColumn) ? null : BigDecimal.ZERO,
                        "reserved_vie".equals(missingColumn) ? null : BigDecimal.ZERO))
                .satisfies(exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getSQLException().getMessage()).containsIgnoringCase(missingColumn);
                });
    }

    @Test
    @DisplayName("DB는 존재하지 않는 회원의 이용 주기 생성을 거절한다")
    void rejectsNonexistentOwner() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> insertWindow(Long.MAX_VALUE, start, start.plus(30, ChronoUnit.DAYS),
                        new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("DB는 numeric(18,6)의 정수부 범위를 넘는 제공량을 거절한다")
    void rejectsQuantityOutsideNumericPrecision() {
        User user = persistUser("first");
        entityManager.flush();
        Instant start = user.getSignupCompletedAt();

        assertThatExceptionOfType(DataException.class)
                .isThrownBy(() -> insertWindow(user.getId(), start, start.plus(30, ChronoUnit.DAYS),
                        new BigDecimal("1000000000000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("22001", "22003"));
    }

    @Test
    @DisplayName("오래된 버전의 예약 변경은 먼저 저장된 예약량을 덮어쓰지 못한다")
    void rejectsReservationFromStaleVersion() {
        AccountUsageWindow window = persistWindow("first");
        Long id = window.getId();
        entityManager.clear();
        AccountUsageWindow stale = entityManager.find(AccountUsageWindow.class, id);
        entityManager.detach(stale);
        AccountUsageWindow current = entityManager.find(AccountUsageWindow.class, id);
        current.reserveVie(new BigDecimal("200"));
        entityManager.flush();
        entityManager.clear();
        stale.reserveVie(new BigDecimal("300"));

        // [NOTE] 버전 충돌 검증이며 동시 요청의 재시도·정산 처리는 후속 서비스 테스트에서 다룬다.
        assertThatExceptionOfType(OptimisticLockException.class)
                .isThrownBy(() -> {
                    entityManager.merge(stale);
                    entityManager.flush();
                });
    }

    @Test
    @DisplayName("이용 주기를 삭제해도 소유 회원은 유지된다")
    void deletingWindowDoesNotDeleteOwner() {
        AccountUsageWindow window = persistWindow("first");
        Long userId = window.getUser().getId();

        entityManager.remove(window);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(AccountUsageWindow.class, window.getId())).isNull();
        assertThat(entityManager.find(User.class, userId)).isNotNull();
    }

    @Test
    @DisplayName("DB는 이용 주기가 참조하는 회원의 삭제를 거절한다")
    void rejectsDeletingReferencedOwner() {
        AccountUsageWindow window = persistWindow("first");

        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> entityManager.createNativeQuery("delete from users where id = :id")
                        .setParameter("id", window.getUser().getId()).executeUpdate())
                .satisfies(exception -> assertThat(exception.getSQLState()).isIn("23503", "23506"));
    }

    @Test
    @DisplayName("AI 요청의 소유권 참조에 필요한 회원·이용 주기 복합 유일 키를 생성한다")
    void createsOwnershipUniqueKey() {
        List<?> columns = entityManager.createNativeQuery("""
                select column_name
                from information_schema.key_column_usage
                where lower(table_name) = 'account_usage_windows'
                  and lower(constraint_name) = 'uk_account_usage_windows_user_id'
                order by ordinal_position
                """).getResultList();

        assertThat(columns.stream().map(column -> column.toString().toLowerCase(Locale.ROOT)).toList())
                .containsExactly("user_id", "id");
    }

    private AccountUsageWindow persistWindow(String suffix) {
        AccountUsageWindow window = AccountUsageWindow.of(persistUser(suffix));
        entityManager.persist(window);
        entityManager.flush();
        return window;
    }

    private User persistUser(String suffix) {
        User user = User.of("user-" + suffix, suffix + "@example.com", "encoded-password");
        entityManager.persist(user);
        return user;
    }

    private AccountUsageWindow findWindow(Long userId) {
        return entityManager.createQuery("select w from AccountUsageWindow w where w.user.id = :userId", AccountUsageWindow.class)
                .setParameter("userId", userId).getSingleResult();
    }

    // [WHY] 생성자와 예약 메서드의 검증을 우회해 실제 컬럼·CHECK·외래키 제약을 확인한다.
    private void insertWindow(Long userId, Instant start, Instant end, BigDecimal allowance, BigDecimal used, BigDecimal reserved) {
        entityManager.createNativeQuery("""
                insert into account_usage_windows
                    (user_id, period_start_at, period_end_at, allowance_vie, used_vie, reserved_vie, version, created_at, updated_at)
                values
                    (:userId, :start, :end, :allowance, :used, :reserved, 0, current_timestamp, current_timestamp)
                """)
                .setParameter("userId", userId)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("allowance", allowance)
                .setParameter("used", used)
                .setParameter("reserved", reserved)
                .executeUpdate();
    }
}
