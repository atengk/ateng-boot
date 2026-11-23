package io.github.atengk.boot.sample.redis.model.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Stream 待决消息详细条目视图对象 (XPENDING detail)
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待决消息 ID 字符串
     */
    private String idAsString;

    /**
     * 持有该消息的消费者名称
     */
    private String consumerName;

    /**
     * 闲置未确认时长（毫秒）
     */
    private Long idleTimeMs;

    /**
     * 已投递尝试次数
     */
    private Long deliveryCount;
}
