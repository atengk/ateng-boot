package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * RocketMQ 消费端点元数据视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerEndpointVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 端点唯一标识 ID（例如 consumerGroup#topic）
     */
    private String endpointId;

    /**
     * 目标主题
     */
    private String topic;

    /**
     * 消费组
     */
    private String consumerGroup;

    /**
     * 过滤类型（TAG / SQL92）
     */
    private String filterType;

    /**
     * 过滤表达式
     */
    private String filterExpression;

    /**
     * 消费并发数
     */
    private Integer consumptionThreadCount;

    /**
     * 是否处于动态暂停削峰状态
     */
    private boolean paused;

    /**
     * 底层 PushConsumer 物理连接是否在运行
     */
    private boolean running;
}
