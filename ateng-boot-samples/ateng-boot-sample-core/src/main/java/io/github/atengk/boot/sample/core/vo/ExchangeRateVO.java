package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 跨境汇率查询响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 货币对代码 (如 USD/CNY)
     */
    private String currencyPair;

    /**
     * 汇率数值
     */
    private BigDecimal rate;

    /**
     * 实际总调用尝试次数（包含初始调用与重试）
     */
    private int attempts;

    /**
     * 是否触发了 Fallback 降级保底汇率
     */
    private boolean fallbackTriggered;

    /**
     * 汇率数据来源说明 (LIVE_GATEWAY 或 FALLBACK_CACHE)
     */
    private String source;

    /**
     * 重试过程度量日志追踪
     */
    private List<String> retryLogs;
}
