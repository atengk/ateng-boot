package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.rocketmq.listener.RocketmqListenerEndpoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

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

    /**
     * 从 RocketmqListenerEndpoint 构造视图对象
     *
     * @param ep 监听端点模型
     * @return 视图对象
     */
    public static ConsumerEndpointVO from(RocketmqListenerEndpoint ep) {
        if (ep == null) {
            return null;
        }
        String filterType = StringUtils.hasText(ep.getSql92()) ? "SQL92" : "TAG";
        String filterExpr = StringUtils.hasText(ep.getSql92()) ? ep.getSql92()
                : (StringUtils.hasText(ep.getTag()) ? ep.getTag() : "*");
        return ConsumerEndpointVO.builder()
                .endpointId(ep.getId())
                .topic(ep.getTopic())
                .consumerGroup(ep.getConsumerGroup())
                .filterType(filterType)
                .filterExpression(filterExpr)
                .consumptionThreadCount(ep.getConsumptionThreadCount())
                .paused(ep.isPaused())
                .running(ep.isRunning())
                .build();
    }
}
