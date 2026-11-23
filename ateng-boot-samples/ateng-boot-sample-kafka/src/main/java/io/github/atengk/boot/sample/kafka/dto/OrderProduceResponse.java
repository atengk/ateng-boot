package io.github.atengk.boot.sample.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单可靠投递响应 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProduceResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 投递目标主题
     */
    private String topic;

    /**
     * 物理分区
     */
    private Integer partition;

    /**
     * 写入位移 Offset
     */
    private Long offset;

    /**
     * 订单业务ID
     */
    private String orderId;

    /**
     * 投递摘要说明
     */
    private String summary;
}
