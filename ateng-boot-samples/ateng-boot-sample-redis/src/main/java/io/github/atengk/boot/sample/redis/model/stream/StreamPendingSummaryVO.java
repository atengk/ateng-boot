package io.github.atengk.boot.sample.redis.model.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Stream 消费组待决消息概要统计视图对象 (XPENDING summary)
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamPendingSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Stream 键名
     */
    private String stream;

    /**
     * 消费组名称
     */
    private String group;

    /**
     * 待决消息总条数
     */
    private Long totalPending;

    /**
     * 最小待决消息 ID
     */
    private String minId;

    /**
     * 最大待决消息 ID
     */
    private String maxId;

    /**
     * 每个消费者未确认待决消息条数映射
     */
    private Map<String, Long> consumerSummary;
}
