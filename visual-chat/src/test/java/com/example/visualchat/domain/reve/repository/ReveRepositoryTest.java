package com.example.visualchat.domain.reve.repository;

import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.service.ReveService;
import com.example.visualchat.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** [ROLE] 활성 체험 후보의 DB 조회 조건과 20명 더미 후보의 선택 흐름을 검증 */
@DataJpaTest
@Import({JpaAuditingConfig.class, ReveService.class})
class ReveRepositoryTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ReveRepository reveRepository;
    @Autowired
    private ReveService reveService;
    @MockitoBean
    private RandomGenerator randomGenerator;

    @Test
    @DisplayName("서버 코드 순서와 관계없이 활성 후보만 ID 오름차순으로 조회한다")
    void queriesOnlyActiveCandidatesInIdOrder() {
        Reve first = persist("Z_FIRST", BigDecimal.ONE);
        Reve inactive = persist("A_INACTIVE", BigDecimal.TEN);
        inactive.exceptTrial();
        Reve last = persist("B_LAST", new BigDecimal("0.25"));
        entityManager.flush();
        entityManager.clear();

        List<Reve> candidates = reveRepository.findAllByTrialEnabledTrueOrderByIdAsc();

        assertThat(candidates).extracting(Reve::getId).containsExactly(first.getId(), last.getId());
        assertThat(candidates).allSatisfy(reve -> assertThat(reve.getTrialEnabled()).isTrue());
    }

    @Test
    @DisplayName("레브가 없는 DB에서는 체험 후보를 빈 목록으로 조회한다")
    void returnsEmptyWhenNoRevesExist() {
        assertThat(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).isEmpty();
    }

    @Test
    @DisplayName("모든 레브가 체험 제외 상태이면 후보를 빈 목록으로 조회한다")
    void returnsEmptyWhenAllRevesAreExcluded() {
        Reve reve = persist("EXCLUDED", BigDecimal.ONE);
        reve.exceptTrial();
        entityManager.flush();
        entityManager.clear();

        assertThat(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("20명 더미 후보의 동일·차등 가중치 설정에서 비활성 레브를 제외하고 마지막 구간을 선택한다")
    void selectsFromTwentyStoredDummyCandidates(boolean differentWeights) {
        List<Long> activeIds = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            BigDecimal weight = differentWeights ? BigDecimal.valueOf(index) : BigDecimal.ONE;
            Reve reve = persist("DUMMY_%02d".formatted(index), weight);
            activeIds.add(reve.getId());
        }
        // [WHY] 큰 가중치의 비활성 레브가 추첨 합계와 결과에 포함되지 않는지 함께 확인
        Reve inactive = persist("INACTIVE_LARGE", new BigDecimal("999999.999999"));
        inactive.exceptTrial();
        entityManager.flush();
        entityManager.clear();
        when(randomGenerator.nextDouble()).thenReturn(Math.nextDown(1.0));

        List<Reve> candidates = reveService.findTrialCandidates();
        Reve selected = reveService.selectTrialReve();

        assertThat(candidates).hasSize(20).extracting(Reve::getId).containsExactlyElementsOf(activeIds);
        assertThat(selected.getId()).isEqualTo(activeIds.getLast());
        assertThat(selected.getTrialEnabled()).isTrue();
        assertThat(selected.getCode()).isEqualTo("DUMMY_20");
    }

    private Reve persist(String code, BigDecimal weight) {
        Reve reve = Reve.of(code, "더미 레브 " + code, "테스트 소개", "테스트 설정", weight);
        entityManager.persist(reve);
        return reve;
    }
}
