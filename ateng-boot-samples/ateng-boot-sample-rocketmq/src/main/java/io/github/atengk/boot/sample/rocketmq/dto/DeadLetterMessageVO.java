package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 死信消息详情视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterMessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一 ID
     */
    private String messageId;

    /**
     * 死信主题（例如 %DLQ%GID_ORDER）
     */
    private String dlqTopic;

    /**
     * 原始主题
     */
    private String originalTopic;

    /**
     * 业务 Key
     */
    private String bizKey;

    /**
     * Tag 标签
     */
    private String tag;

    /**
     * 业务 Keys 列表
     */
    private List<String> keys;

    /**
     * 消息体文本
     */
    private String body;

    /**
     * 用户自定义扩展协议头
     */
    private Map<String, String> userProperties;

    /**
     * 投递尝试次数
     */
    private Integer deliveryAttempt;

    /**
     * 已累计重放次数
     */
    private Integer replayCount;
}
