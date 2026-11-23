package io.github.atengk.boot.sms.model;

import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 只读探查短信渠道账户余额与余量结果值对象
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
@Builder
public class SmsBalanceResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前短信厂商驱动是否支持只读余额查询
     */
    private final boolean supported;

    /**
     * 渠道名称
     */
    private final String channelName;

    /**
     * 云厂商枚举标识
     */
    private final SmsSupplier supplier;

    /**
     * 账户货币余额（按金额计费平台返回，如人民币元）
     */
    private final BigDecimal balance;

    /**
     * 账户剩余可用短信条数（按条数计费平台返回）
     */
    private final Long remainingCount;

    /**
     * 计量单位标识（如 CNY、COUNT、MOCK）
     */
    private final String unit;

    /**
     * 语义化描述信息
     */
    private final String message;

    /**
     * 构造不支持余额探查时的明确契约响应对象
     *
     * @param channelName 渠道名称
     * @param supplier    云厂商标识
     * @param reason      不支持说明
     * @return SmsBalanceResult (supported=false)
     */
    public static SmsBalanceResult unsupported(String channelName, SmsSupplier supplier, String reason) {
        return SmsBalanceResult.builder()
                .supported(false)
                .channelName(channelName)
                .supplier(supplier)
                .message(reason)
                .build();
    }
}
