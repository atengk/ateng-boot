package io.github.atengk.boot.sample.sms.vo;

import io.github.atengk.boot.sms.model.SmsBalanceResult;
import io.github.atengk.boot.sms.model.SmsSupplier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 短信渠道账户余额探查响应视图对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsBalanceVO {

    private boolean supported;
    private String channelName;
    private SmsSupplier supplier;
    private BigDecimal balance;
    private Long remainingCount;
    private String unit;
    private String message;

    public static SmsBalanceVO fromResult(SmsBalanceResult res) {
        if (res == null) {
            return null;
        }
        return SmsBalanceVO.builder()
                .supported(res.isSupported())
                .channelName(res.getChannelName())
                .supplier(res.getSupplier())
                .balance(res.getBalance())
                .remainingCount(res.getRemainingCount())
                .unit(res.getUnit())
                .message(res.getMessage())
                .build();
    }
}
