package io.github.atengk.boot.ai.properties;

import lombok.Data;

/**
 * AI 大模型调用全维可观测审计与脱敏配置属性
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
public class AiAuditProperties {

    /**
     * 是否启用大模型调用可观测审计与监听器 (基于 LangChain4j ChatModelListener)
     */
    private boolean enabled = true;

    /**
     * 是否对出入参（Prompt、Completion）中的手机号、身份证、银行卡等 PII 敏感信息实施正则掩码脱敏
     */
    private boolean maskPii = true;

    /**
     * 是否在审计日志中输出完整 Payload（请求文本与回答文本），生产环境建议关闭以防日志打爆磁盘
     */
    private boolean logPayload = false;

    /**
     * 默认模型定价配置 (当未在 pricings 中匹配到特定模型时回退保底使用)
     */
    private ModelPricingConfig defaultPricing = new ModelPricingConfig();

    /**
     * 多模型定价配置字典 (key 为模型标识名称或别名，例如 gpt-4o, deepseek-chat, qwen-max 等)
     */
    private java.util.Map<String, ModelPricingConfig> pricings = new java.util.LinkedHashMap<>();
}
