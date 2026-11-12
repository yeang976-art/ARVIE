package com.example.visualchat.domain.ai_request.metering;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** [ROLE] 확정된 달러당 환산율과 캐시·긴 문맥 단가 및 예약 상한을 검증 */
class TerraVieCalculatorTest {
    private final TerraVieCalculator calculator = new TerraVieCalculator();

    @ParameterizedTest
    @CsvSource({
            "0,0,0,0,0,0",
            "1000,0,0,0,0,10",
            "1000,1000,0,0,0,0.5",
            "1000,0,1000,0,0,12.5",
            "1000,0,0,1000,0,25",
            "0,0,0,0,200,10",
            "1000,0,0,0,200,20",
            "12000,4000,3000,4000,1000,199.5",
            "272000,0,0,0,200,2730",
            "272001,0,0,0,200,5455.02",
            "272001,272001,0,0,200,287.001",
            "272001,0,272001,0,200,6815.025",
            "272001,0,0,272001,200,13615.05"
    })
    @DisplayName("실제 유형별 비용을 1달러당 10,000 VIE로 환산하고 경계 초과에만 할증한다")
    void calculatesDocumentedCosts(long input, long cached, long write30m, long write12h, long output, String expected) {
        BigDecimal vie = calculator.calculate(new AiTokenUsage(input, cached, write30m, write12h, output));
        assertThat(vie).isEqualByComparingTo(expected);
        assertThat(vie.scale()).isEqualTo(6);
    }

    @Test
    @DisplayName("같은 출력 총토큰에 추론 토큰을 중복 추가하지 않고 전달된 총량만 계산한다")
    void chargesReportedOutputTotalOnce() {
        assertThat(calculator.calculate(new AiTokenUsage(0, 0, 0, 0, 300))).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("입력과 출력 상한으로 예약하며 평균 200 VIE를 기본값으로 사용하지 않는다")
    void reservesFromResourceLimits() {
        assertThat(calculator.estimateReservation(1000, 500)).isEqualByComparingTo("50");
        assertThat(calculator.estimateReservation(0, 0)).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @CsvSource({"1000,500", "272000,500", "272001,500"})
    @DisplayName("예약 상한은 캐시 유형과 실제 출력 감소에 관계없이 최종 차감량 이상이다")
    void reservationCoversSupportedUsageWithinBudget(long input, long maxOutput) {
        BigDecimal reservation = calculator.estimateReservation(input, maxOutput);
        for (long output : new long[]{0, maxOutput / 2, maxOutput}) {
            for (AiTokenUsage usage : new AiTokenUsage[]{
                    new AiTokenUsage(input, 0, 0, 0, output),
                    new AiTokenUsage(input, input, 0, 0, output),
                    new AiTokenUsage(input, 0, input, 0, output),
                    new AiTokenUsage(input, 0, 0, input, output),
                    new AiTokenUsage(input, input / 4, input / 4, input / 4, output)
            }) {
                assertThat(calculator.calculate(usage)).isLessThanOrEqualTo(reservation);
            }
        }
    }

    @Test
    @DisplayName("긴 문맥 상한으로 예약하면 실제 입력이 일반 단가 구간으로 줄어도 충분하다")
    void reservationCoversCrossingBackBelowLongContextThreshold() {
        assertThat(calculator.calculate(new AiTokenUsage(272000, 0, 0, 0, 500)))
                .isLessThanOrEqualTo(calculator.estimateReservation(272001, 500));
    }

    @Test
    @DisplayName("사용량 누락을 0 VIE 성공으로 바꾸지 않는다")
    void rejectsMissingUsage() {
        assertInvalid(() -> calculator.calculate(null));
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "9223372036854775807,0", "0,9223372036854775807"})
    @DisplayName("음수 상한과 저장 범위를 넘는 계산 결과는 예약 전에 거절한다")
    void rejectsInvalidOrUnrepresentableReservation(long input, long output) {
        assertInvalid(() -> calculator.estimateReservation(input, output));
    }

    private void assertInvalid(Runnable operation) {
        assertThatExceptionOfType(ServiceException.class).isThrownBy(operation::run)
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }
}
