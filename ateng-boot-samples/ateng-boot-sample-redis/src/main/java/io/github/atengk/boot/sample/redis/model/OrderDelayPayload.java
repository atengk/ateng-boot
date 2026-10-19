package io.github.atengk.boot.sample.redis.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单超时延时取消消息载荷
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayPayload implements Serializable {

    /**
     * 订单编号
     */
    private String orderId;

    /**
     * 超时关单原因
     */
    private String reason;

    /**
     * 任务创建时间戳
     */
    private Long timestamp;
}
