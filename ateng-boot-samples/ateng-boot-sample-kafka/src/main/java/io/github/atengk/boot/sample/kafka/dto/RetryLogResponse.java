package io.github.atengk.boot.sample.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 阶梯延迟重试执行轨迹日志响应 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryLogResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 接收主题
     */
    private String topic;

    /**
     * 业务分区键
     */
    private String key;

    /**
     * 消息载荷
     */
    private String payload;

    /**
     * 当前尝试消费轮次
     */
    private Integer attempt;

    /**
     * 链路追踪 ID
     */
    private String traceId;

    /**
     * 租户标识
     */
    private String tenantId;

    /**
     * 用户标识
     */
    private String userId;
}
