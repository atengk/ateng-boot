package io.github.atengk.boot.sample.redis.model.pubsub;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Pub/Sub 广播消息发布结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标主题或频道
     */
    private String topic;

    /**
     * 接收订阅客户端数
     */
    private Long subscriberCount;

    /**
     * 是否发布成功
     */
    private Boolean success;
}
