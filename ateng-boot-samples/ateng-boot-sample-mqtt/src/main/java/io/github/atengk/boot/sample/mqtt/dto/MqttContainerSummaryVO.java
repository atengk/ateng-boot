package io.github.atengk.boot.sample.mqtt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 监听容器治理与可靠性运维状态汇总 VO
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MqttContainerSummaryVO implements Serializable {

    /**
     * 当前处于暂停状态的端点 ID 列表
     */
    private List<String> pausedEndpoints;

    /**
     * 主客户端当前在 Broker 侧保持活跃的订阅主题过滤器列表
     */
    private List<String> subscribedTopics;

    /**
     * 通过 AckMode.MANUAL 手动 ack() 确认的消费载荷列表
     */
    private List<String> manualAckedMessages;

    /**
     * 经历瞬态故障重试后最终恢复成功的载荷列表
     */
    private List<String> retriedMessages;

    /**
     * 受管退避重试各次尝试轨迹记录（如 attempt-1:xxx、attempt-2:xxx）
     */
    private List<String> retryAttemptTraces;

    /**
     * 共享流控通道各活跃实例实际收到的消费记录
     */
    private List<String> sharedFlowMessages;
}
