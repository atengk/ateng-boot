package io.github.atengk.boot.rocketmq.model;

import org.springframework.util.StringUtils;

import java.io.Serial;
import java.io.Serializable;

/**
 * RocketMQ 消费监听容器运行期状态不可变快照模型
 *
 * <p>由 {@code RocketmqContainerManager#getContainerStatuses()} 与
 * {@code RocketmqContainerManager#getContainerStatus(String)} 产出，
 * 完整反映端点订阅过滤配置、并发配额、运行/暂停状态与运维流控模式。</p>
 *
 * @param endpointId             监听端点唯一标识 ID
 * @param topic                  目标订阅主题
 * @param consumerGroup          消费者组名称
 * @param filterType             服务端过滤类型（{@code TAG} / {@code SQL92}）
 * @param filterExpression       生效的服务端过滤表达式
 * @param tag                    Tag 过滤表达式
 * @param sql92                  SQL92 属性过滤表达式
 * @param consumptionThreadCount 消费并发处理线程数
 * @param maxCacheMessageCount   客户端本地最大缓存消息数
 * @param running                底层物理 PushConsumer 是否处于运行状态
 * @param paused                 是否处于动态暂停削峰状态（保持连接并返回 FAILURE 触发 Broker 阶梯退避）
 * @param autoStartup            是否随应用容器自动启动
 * @param listenerMode           监听消费模式（例如 {@code PUSH_CONSUMER}）
 * @param statusDescription      运行期运维状态说明
 * @author Ateng
 * @since 2026-11-15
 */
public record RocketmqContainerStatus(
        String endpointId,
        String topic,
        String consumerGroup,
        String filterType,
        String filterExpression,
        String tag,
        String sql92,
        int consumptionThreadCount,
        int maxCacheMessageCount,
        boolean running,
        boolean paused,
        boolean autoStartup,
        String listenerMode,
        String statusDescription
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 规范构造函数：确保过滤属性、监听模式与状态说明字段空安全
     */
    public RocketmqContainerStatus {
        tag = tag == null ? "" : tag;
        sql92 = sql92 == null ? "" : sql92;
        if (!StringUtils.hasText(filterType)) {
            filterType = StringUtils.hasText(sql92) ? "SQL92" : "TAG";
        }
        if (!StringUtils.hasText(filterExpression)) {
            filterExpression = StringUtils.hasText(sql92) ? sql92 : (StringUtils.hasText(tag) ? tag : "*");
        }
        if (!StringUtils.hasText(listenerMode)) {
            listenerMode = "PUSH_CONSUMER";
        }
        statusDescription = statusDescription == null ? "" : statusDescription;
    }
}
