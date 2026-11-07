package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 跨境汇率查询与弹性重试请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 源币种 (如 USD, EUR, GBP)
     */
    @NotBlank(message = "源币种不能为空")
    private String fromCurrency;

    /**
     * 目标币种 (如 CNY)
     */
    @NotBlank(message = "目标币种不能为空")
    private String toCurrency;

    /**
     * 模拟前置失败次数（0 表示一次成功，1~2 表示重试后成功，>=3 超过最大重试次数将触发 Fallback 降级）
     */
    private int simulateFailures;
}
