package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单消息发送统一响应 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProduceResponse {

    /**
     * 业务订单号
     */
    private String orderId;

    /**
     * RocketMQ 物理消息 ID
     */
    private String messageId;

    /**
     * 消息 Tag 标签
     */
    private String tag;

    /**
     * 发送状态描述
     */
    private String status;
}
