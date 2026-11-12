package com.example.visualchat.domain.ai_request.metering;

import com.example.visualchat.common.exception.ErrorCode;
import com.example.visualchat.common.exception.ServiceException;
import com.example.visualchat.domain.ai_request.model.AiTokenUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** [ROLE] Terra Standard 토큰 사용 비용과 예약 상한을 VIE로 환산 */
@Component
public class TerraVieCalculator {

    public static final String MODEL_NAME = "gpt-6.1-terra";
    public static final String VERSION = "terra-v1";

    // [NOTE] 단가는 100만 토큰당 달러 기준
    private static final BigDecimal INPUT_PRICE = new BigDecimal("1.00");
    private static final BigDecimal CACHED_INPUT_PRICE = new BigDecimal("0.05");
    private static final BigDecimal CACHE_WRITE_30M_PRICE = new BigDecimal("1.25");
    private static final BigDecimal CACHE_WRITE_12H_PRICE = new BigDecimal("2.50");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("5.00");

    private static final BigDecimal VIE_PER_DOLLAR = new BigDecimal("10000");

    private static final long LONG_CONTEXT_THRESHOLD = 272_000L;
    private static final BigDecimal LONG_INPUT_MULTIPLIER = new BigDecimal("2");
    private static final BigDecimal LONG_OUTPUT_MULTIPLIER = new BigDecimal("1.5");

    private static final int VIE_SCALE = 6;
    private static final int VIE_PRECISION = 18;

    // [TOPIC] 실제 사용량에 따른 최종 VIE 계산
    public BigDecimal calculate(AiTokenUsage usage) {
        if (usage == null) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "토큰 사용량이 필요합니다.");
        }

        BigDecimal inputCost = tokenCost(usage.ordinaryInputTokens(), INPUT_PRICE)
                .add(tokenCost(usage.cachedInputTokens(), CACHED_INPUT_PRICE))
                .add(tokenCost(usage.cacheWrite30mTokens(), CACHE_WRITE_30M_PRICE))
                .add(tokenCost(usage.cacheWrite12hTokens(), CACHE_WRITE_12H_PRICE));

        BigDecimal outputCost = tokenCost(usage.outputTokens(), OUTPUT_PRICE);

        // [NOTE] 입력이 272,000토큰을 초과하면 요청 전체에 긴 문맥 단가 적용
        if (usage.inputTokens() > LONG_CONTEXT_THRESHOLD) {
            inputCost = inputCost.multiply(LONG_INPUT_MULTIPLIER);
            outputCost = outputCost.multiply(LONG_OUTPUT_MULTIPLIER);
        }

        // [WHY] 중간 계산은 반올림하지 않고 최종 합계에서 한 번만 반올림
        BigDecimal vie = inputCost
                .add(outputCost)
                .multiply(VIE_PER_DOLLAR)
                .setScale(VIE_SCALE, RoundingMode.HALF_UP);

        if (vie.precision() > VIE_PRECISION) {
            throw new ServiceException(ErrorCode.INVALID_INPUT, "환산 결과가 저장 가능한 VIE 범위를 초과했습니다.");
        }

        return vie;
    }

    // [TOPIC] 입력·출력 상한을 기준으로 예약량 계산
    public BigDecimal estimateReservation(long inputTokenUpperBound, long maxOutputTokens) {
        // [WHY] 지원하는 입력 유형 중 가장 비싼 12시간 캐시 쓰기로 상한을 계산
        // [NOTE] 실제 정산에서는 제공자가 보고한 사용량으로 다시 계산
        return calculate(new AiTokenUsage(
                inputTokenUpperBound,
                0,
                0,
                inputTokenUpperBound,
                maxOutputTokens
        ));
    }

    private BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .movePointLeft(6);
    }
}