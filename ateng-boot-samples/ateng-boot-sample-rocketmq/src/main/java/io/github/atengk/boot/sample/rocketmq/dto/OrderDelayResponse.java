package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 延时消息发送统一响应 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayResponse {

    /**
     * 业务订单号
     */
    private String orderId;

    /**
     * RocketMQ 物理消息 ID
     */
    private String messageId;

    /**
     * 延时秒数
     */
    private Long delaySeconds;

    /**
     * 预期投递时间戳（毫秒）
     */
    private Long deliverAtTimestamp;

    /**
     * 延时精准撤回句柄（用于 recallMessage）
     */
    private String recallHandle;

    /**
     * 调度状态描述
     */
    private String status;
}
