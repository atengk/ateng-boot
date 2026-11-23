package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * SimpleConsumer 消息展示响应视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleMessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一 ID
     */
    private String messageId;

    /**
     * 所属主题
     */
    private String topic;

    /**
     * 消息 Tag
     */
    private String tag;

    /**
     * 业务 Keys 索引列表
     */
    private List<String> keys;

    /**
     * 消息分组 MessageGroup (FIFO)
     */
    private String messageGroup;

    /**
     * 消息正文文本
     */
    private String body;

    /**
     * 出生时间戳
     */
    private Long bornTimestamp;

    /**
     * 投递尝试次数
     */
    private Integer deliveryAttempt;
}
