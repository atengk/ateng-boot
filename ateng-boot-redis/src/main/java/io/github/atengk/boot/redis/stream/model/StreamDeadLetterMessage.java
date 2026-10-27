package io.github.atengk.boot.redis.stream.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Redis Stream 消费失败死信消息结构体
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamDeadLetterMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始 Stream 键名
     */
    private String stream;

    /**
     * 原始消费组名称
     */
    private String group;

    /**
     * 原始消费者名称
     */
    private String consumer;

    /**
     * 原始消息唯一 ID
     */
    private String recordId;

    /**
     * 原始消息体载荷
     */
    private Object payload;

    /**
     * 失败重试次数
     */
    private int retryCount;

    /**
     * 异常发生时间
     */
    private LocalDateTime failedTime;

    /**
     * 异常类名
     */
    private String exceptionClass;

    /**
     * 异常摘要与堆栈信息
     */
    private String exceptionMessage;
}
