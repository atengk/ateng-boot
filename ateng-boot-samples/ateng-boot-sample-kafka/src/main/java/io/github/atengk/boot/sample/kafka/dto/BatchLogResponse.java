package io.github.atengk.boot.sample.kafka.dto;

import io.github.atengk.boot.sample.kafka.model.OrderMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量日志消费记录响应 VO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchLogResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分区键 Key
     */
    private String key;

    /**
     * 消费位移 Offset
     */
    private Long offset;

    /**
     * 订单消息内容
     */
    private OrderMessage order;

    /**
     * 当前记录由 KafkaRecordExecutor 隔离还原的租户上下文 ID
     */
    private String tenantId;
}
