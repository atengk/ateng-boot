package io.github.atengk.boot.ai.observability;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 结构化大模型可观测审计记录契约模型
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Value
@Builder
public class AiAuditRecord {

    /**
     * 全链路追踪标识 traceId
     */
    String traceId;

    /**
     * 操作用户标识
     */
    String userId;

    /**
     * 多租户标识
     */
    String tenantId;

    /**
     * 模型名称 (如 deepseek-chat, gpt-4o 等)
     */
    String modelName;

    /**
     * 请求执行耗时 (毫秒)
     */
    long durationMs;

    /**
     * 请求发起时间
     */
    Instant requestTime;

    /**
     * 响应完成或异常抛出时间
     */
    Instant responseTime;

    /**
     * 调用终态状态 (SUCCESS, ERROR, CANCELLED)
     */
    String status;

    /**
     * 输入提示词 Token 消耗
     */
    Integer promptTokens;

    /**
     * 输出回答 Token 消耗
     */
    Integer completionTokens;

    /**
     * 总消耗 Token 数
     */
    Integer totalTokens;

    /**
     * Token 是否由保底估算器推算生成
     */
    boolean estimated;

    /**
     * 脱敏后的输入提示词内容 (当 logPayload=false 时可为脱敏摘要或元数据)
     */
    String promptText;

    /**
     * 脱敏后的输出模型回答内容 (当 logPayload=false 时可为脱敏摘要或元数据)
     */
    String responseText;

    /**
     * 异常错误信息 (成功时为 null)
     */
    String errorMessage;

    /**
     * 扩展元数据字典
     */
    @Builder.Default
    Map<String, Object> metadata = Collections.emptyMap();
}
