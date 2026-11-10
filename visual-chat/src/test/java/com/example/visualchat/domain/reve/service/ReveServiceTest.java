package com.example.visualchat.domain.reve.service;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.reve.entity.Reve;
import com.example.visualchat.domain.reve.repository.ReveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** [ROLE] 체험 후보 조회와 고정 난수를 이용한 가중 선택의 경계·실패 규칙을 검증 */
class ReveServiceTest {

    private final ReveRepository reveRepository = mock(ReveRepository.class);
    private final RandomGenerator randomGenerator = mock(RandomGenerator.class);
    private ReveService service;

    @BeforeEach
    void setUp() {
        service = new ReveService(reveRepository, randomGenerator);
    }

    // [TOPIC] 후보 조회와 빈 후보 처리
    @Test
    @DisplayName("활성 후보 조회는 저장소가 제공한 후보 순서와 원본 설정을 유지한다")
    void returnsOrderedTrialCandidates() {
        List<Reve> candidates = weightedCandidates("1", "3", "6");
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);

        assertThat(service.findTrialCandidates()).containsExactlyElementsOf(candidates);
        verifyNoInteractions(randomGenerator);
    }

    @Test
    @DisplayName("활성 후보가 없으면 배정 불가 업무 오류를 반환한다")
    void rejectsEmptyCandidateQuery() {
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(List.of());

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(service::findTrialCandidates)
                .satisfies(error -> assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.TRIAL_REVE_UNAVAILABLE));
        verifyNoInteractions(randomGenerator);
    }

    @Test
    @DisplayName("후보가 없는 추첨은 난수를 소비하기 전에 실패한다")
    void rejectsSelectionWithoutCandidates() {
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(List.of());

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(service::selectTrialReve)
                .satisfies(error -> assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.TRIAL_REVE_UNAVAILABLE));
        verifyNoInteractions(randomGenerator);
    }

    // [TOPIC] 선택 구간의 시작·끝과 소수 가중치
    @ParameterizedTest
    @CsvSource({
            "0.0, 0",
            "0.09999999999999999, 0",
            "0.1, 1",
            "0.39999999999999997, 1",
            "0.4, 2",
            "0.9999999999999999, 2"
    })
    @DisplayName("1·3·6 가중치는 구간 시작을 포함하고 끝을 제외하여 선택한다")
    void selectsWeightedIntervalAtBoundaries(double randomValue, int expectedIndex) {
        List<Reve> candidates = weightedCandidates("1", "3", "6");
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);
        when(randomGenerator.nextDouble()).thenReturn(randomValue);

        Reve selected = service.selectTrialReve();

        assertThat(selected).isSameAs(candidates.get(expectedIndex));
        verify(randomGenerator).nextDouble();
        // [WHY] 선택 단계에서 배정 기록이나 레브 원본을 저장하지 않는 책임 경계를 검증
        verify(reveRepository).findAllByTrialEnabledTrueOrderByIdAsc();
        verifyNoMoreInteractions(reveRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "0.0, 0",
            "0.09999999999999999, 0",
            "0.1, 1",
            "0.39999999999999997, 1",
            "0.4, 2",
            "0.9999999999999999, 2"
    })
    @DisplayName("최소 소수 단위 가중치도 1·3·6과 같은 상대 비율로 선택한다")
    void preservesDecimalWeightRatios(double randomValue, int expectedIndex) {
        List<Reve> candidates = weightedCandidates("0.000001", "0.000003", "0.000006");
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);
        when(randomGenerator.nextDouble()).thenReturn(randomValue);

        assertThat(service.selectTrialReve()).isSameAs(candidates.get(expectedIndex));
    }

    @ParameterizedTest
    @CsvSource({"0.0, 0", "0.25, 1", "0.5, 2", "0.75, 3", "0.9999999999999999, 3"})
    @DisplayName("동일 가중치 후보는 같은 크기의 선택 구간을 갖는다")
    void selectsEqualWeightIntervals(double randomValue, int expectedIndex) {
        List<Reve> candidates = List.of(
                reve("A", "1.25"), reve("B", "1.25"),
                reve("C", "1.25"), reve("D", "1.25"));
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);
        when(randomGenerator.nextDouble()).thenReturn(randomValue);

        assertThat(service.selectTrialReve()).isSameAs(candidates.get(expectedIndex));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 0.9999999999999999})
    @DisplayName("후보가 한 명이면 모든 정상 선택 지점에서 해당 레브를 반환한다")
    void selectsOnlyCandidate(double randomValue) {
        Reve candidate = reve("ONLY", "999999.999999");
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(List.of(candidate));
        when(randomGenerator.nextDouble()).thenReturn(randomValue);

        assertThat(service.selectTrialReve()).isSameAs(candidate);
    }

    @Test
    @DisplayName("고정된 20개 구간 중간점을 사용하면 1·3·6 비율의 선택 개수가 재현된다")
    void reproducesWeightedCountsOnFixedGrid() {
        List<Reve> candidates = weightedCandidates("1", "3", "6");
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);
        int[] counts = new int[3];

        // [WHY] 반복 실행마다 달라지는 확률 추정 대신 동일한 입력 격자로 가중치 배분을 검증
        for (int index = 0; index < 20; index++) {
            when(randomGenerator.nextDouble()).thenReturn((index + 0.5) / 20);
            Reve selected = service.selectTrialReve();
            counts[candidates.indexOf(selected)]++;
        }

        assertThat(counts).containsExactly(2, 6, 12);
    }

    // [TOPIC] 잘못된 후보 설정 처리
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "-0.000001"})
    @DisplayName("후보 중 하나라도 가중치가 없거나 0 이하이면 난수 생성 전에 설정 오류로 실패한다")
    void rejectsInvalidWeightBeforeDrawing(String value) {
        Reve invalidCandidate = mock(Reve.class);
        BigDecimal weight = value == null ? null : new BigDecimal(value);
        when(invalidCandidate.getTrialWeight()).thenReturn(weight);
        List<Reve> candidates = List.of(reve("VALID_FIRST", "1"), invalidCandidate, reve("VALID_LAST", "6"));
        when(reveRepository.findAllByTrialEnabledTrueOrderByIdAsc()).thenReturn(candidates);

        assertThatExceptionOfType(ServiceException.class)
                .isThrownBy(service::selectTrialReve)
                .satisfies(error -> assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_TRIAL_REVE_WEIGHT));
        verifyNoInteractions(randomGenerator);
    }

    private List<Reve> weightedCandidates(String first, String second, String third) {
        return List.of(reve("A", first), reve("B", second), reve("C", third));
    }

    private Reve reve(String code, String weight) {
        return Reve.of(code, "더미 레브 " + code, "테스트 소개", "테스트 설정", new BigDecimal(weight));
    }
}
