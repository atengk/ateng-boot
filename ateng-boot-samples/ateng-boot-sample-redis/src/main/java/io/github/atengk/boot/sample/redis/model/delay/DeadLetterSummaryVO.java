package io.github.atengk.boot.sample.redis.model.delay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 延时队列死信积压统计视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始业务队列名
     */
    private String queueName;

    /**
     * 死信队列完整名称
     */
    private String deadLetterQueueName;

    /**
     * 死信堆积条数
     */
    private Integer deadLetterCount;
}
