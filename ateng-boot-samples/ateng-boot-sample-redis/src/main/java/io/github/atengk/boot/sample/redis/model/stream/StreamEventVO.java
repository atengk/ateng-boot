package io.github.atengk.boot.sample.redis.model.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单 Stream 事件处理结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Redis Stream 消息记录 ID
     */
    private String recordId;

    /**
     * 订单编号
     */
    private String orderId;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 事件创建/接收时间
     */
    private LocalDateTime eventTime;
}
