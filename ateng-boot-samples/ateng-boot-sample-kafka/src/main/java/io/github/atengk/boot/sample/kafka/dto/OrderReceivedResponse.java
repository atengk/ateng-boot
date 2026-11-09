package io.github.atengk.boot.sample.kafka.dto;

import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 已接收订单消费明细响应 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReceivedResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务分区键
     */
    private String key;

    /**
     * 分区编号
     */
    private Integer partition;

    /**
     * 消费位移
     */
    private Long offset;

    /**
     * 订单载荷消息
     */
    private OrderMessage order;

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
