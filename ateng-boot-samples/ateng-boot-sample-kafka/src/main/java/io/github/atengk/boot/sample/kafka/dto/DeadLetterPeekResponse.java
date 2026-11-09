package io.github.atengk.boot.sample.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 死信探查明细响应 VO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterPeekResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 死信主题
     */
    private String topic;

    /**
     * 分区编号
     */
    private Integer partition;

    /**
     * 消费位移
     */
    private Long offset;

    /**
     * 消息键 Key
     */
    private String key;

    /**
     * 消息体载荷
     */
    private String payload;
}
