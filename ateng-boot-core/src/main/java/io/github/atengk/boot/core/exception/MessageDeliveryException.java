package io.github.atengk.boot.core.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import lombok.Getter;

/**
 * 跨中间件消息出站投递异常
 * 封装消息发送端网络故障、Confirm 超时、Broker NACK 或不可路由等异常，保留原始因果链并解耦底层驱动 SDK
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public class MessageDeliveryException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 消息中间件类型标识 (rabbitmq, kafka, rocketmq)
     */
    private final String mqType;

    /**
     * 目标主题或交换机 (topic / exchange)
     */
    private final String topic;

    /**
     * 消息业务唯一标识
     */
    private final String bizKey;

    public MessageDeliveryException(String mqType, String topic, String bizKey, IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause, cause != null);
        this.mqType = mqType;
        this.topic = topic;
        this.bizKey = bizKey;
    }

    public MessageDeliveryException(String mqType, String topic, String bizKey, String message, Throwable cause) {
        this(mqType, topic, bizKey, CommonResultCode.INTERNAL_SERVER_ERROR, message, cause);
    }

    public MessageDeliveryException(String mqType, String topic, String bizKey, String message) {
        this(mqType, topic, bizKey, CommonResultCode.INTERNAL_SERVER_ERROR, message, null);
    }
}
