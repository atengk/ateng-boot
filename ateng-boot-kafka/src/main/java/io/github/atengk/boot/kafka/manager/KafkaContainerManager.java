package io.github.atengk.boot.kafka.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.Lifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Kafka 运行期消费监听容器动态流控与管控门面
 *
 * <p>封装 Spring Kafka 的 {@link KafkaListenerEndpointRegistry}，
 * 为微服务在下游依赖维护、大促前流量削峰、DDL 灰度变更或突发故障自愈场景下
 * 提供极速、安全、确定性的消费暂停（pause）、恢复（resume）、启动（start）与停止（stop）能力，
 * 彻底消除粗暴重启服务或拉黑端口导致的脏位移和重平衡震荡。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Slf4j
public class KafkaContainerManager {

    private final KafkaListenerEndpointRegistry registry;

    /**
     * 构建消费监听容器管理门面
     *
     * @param registry Spring Kafka 监听端点注册中心（可为 null 优雅降级）
     */
    public KafkaContainerManager(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    // =========================================================================
    // 1. 单目标容器流控 (Targeted Flow Control: By Listener ID or Topic)
    // =========================================================================

    /**
     * 暂停指定监听端点或订阅目标主题的所有消费容器
     *
     * <p>调用后 Consumer 实例将保持与 Broker 的 Heartbeat 心跳连接，
     * 但暂停 poll() 消息拉取，不触发消费组 Rebalance，使消息在 Broker 队列安全累积。</p>
     *
     * @param idOrTopic 监听容器 ID（如 {@code @KafkaListener(id = "...")}）或主题名称
     * @return 若找到并成功执行暂停操作返回 {@code true}，若未找到任何容器返回 {@code false}
     */
    public boolean pause(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            log.warn("[MQ-CONTAINER-PAUSE] 未找到匹配的消费监听容器: idOrTopic={}", idOrTopic);
            return false;
        }

        int pausedCount = 0;
        for (MessageListenerContainer container : containers) {
            if (!container.isContainerPaused() && !container.isPauseRequested()) {
                container.pause();
                pausedCount++;
            }
        }
        log.info("[MQ-CONTAINER-PAUSE] 成功暂停消费监听容器: idOrTopic={}, matchedCount={}, pausedCount={}",
                idOrTopic, containers.size(), pausedCount);
        return true;
    }

    /**
     * 恢复指定监听端点或订阅目标主题的所有已暂停消费容器
     *
     * @param idOrTopic 监听容器 ID 或主题名称
     * @return 若找到并成功执行恢复操作返回 {@code true}，若未找到任何容器返回 {@code false}
     */
    public boolean resume(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            log.warn("[MQ-CONTAINER-RESUME] 未找到匹配的消费监听容器: idOrTopic={}", idOrTopic);
            return false;
        }

        int resumedCount = 0;
        for (MessageListenerContainer container : containers) {
            if (container.isContainerPaused() || container.isPauseRequested()) {
                container.resume();
                resumedCount++;
            }
        }
        log.info("[MQ-CONTAINER-RESUME] 成功恢复消费监听容器: idOrTopic={}, matchedCount={}, resumedCount={}",
                idOrTopic, containers.size(), resumedCount);
        return true;
    }

    /**
     * 启动指定监听端点或订阅目标主题的所有消费容器
     *
     * @param idOrTopic 监听容器 ID 或主题名称
     * @return 若找到并成功执行启动操作返回 {@code true}，若未找到任何容器返回 {@code false}
     */
    public boolean start(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            log.warn("[MQ-CONTAINER-START] 未找到匹配的消费监听容器: idOrTopic={}", idOrTopic);
            return false;
        }

        int startedCount = 0;
        for (MessageListenerContainer container : containers) {
            if (!container.isRunning()) {
                container.start();
                startedCount++;
            }
        }
        log.info("[MQ-CONTAINER-START] 成功启动消费监听容器: idOrTopic={}, matchedCount={}, startedCount={}",
                idOrTopic, containers.size(), startedCount);
        return true;
    }

    /**
     * 停止指定监听端点或订阅目标主题的所有消费容器（优雅停机并注销 Consumer）
     *
     * @param idOrTopic 监听容器 ID 或主题名称
     * @return 若找到并成功执行停止操作返回 {@code true}，若未找到任何容器返回 {@code false}
     */
    public boolean stop(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            log.warn("[MQ-CONTAINER-STOP] 未找到匹配的消费监听容器: idOrTopic={}", idOrTopic);
            return false;
        }

        int stoppedCount = 0;
        for (MessageListenerContainer container : containers) {
            if (container.isRunning()) {
                container.stop();
                stoppedCount++;
            }
        }
        log.info("[MQ-CONTAINER-STOP] 成功停止消费监听容器: idOrTopic={}, matchedCount={}, stoppedCount={}",
                idOrTopic, containers.size(), stoppedCount);
        return true;
    }

    /**
     * 判断指定监听端点或订阅目标主题的消费容器是否处于暂停状态
     *
     * @param idOrTopic 监听容器 ID 或主题名称
     * @return 若匹配容器全部处于暂停状态返回 {@code true}；若不存在匹配容器或存在运行态容器返回 {@code false}
     */
    public boolean isPaused(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            return false;
        }
        return containers.stream().allMatch(c -> c.isContainerPaused() || c.isPauseRequested());
    }

    /**
     * 判断指定监听端点或订阅目标主题的消费容器是否正在运行
     *
     * @param idOrTopic 监听容器 ID 或主题名称
     * @return 若匹配容器全部处于运行状态且未停止返回 {@code true}；若不存在匹配容器返回 {@code false}
     */
    public boolean isRunning(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        List<MessageListenerContainer> containers = findContainers(idOrTopic);
        if (containers.isEmpty()) {
            return false;
        }
        return containers.stream().allMatch(Lifecycle::isRunning);
    }

    // =========================================================================
    // 2. 全局容器批处理管控 (Global Batch Control)
    // =========================================================================

    /**
     * 暂停当前服务所有已注册的消费监听容器
     *
     * @return 本次成功暂停的容器数量
     */
    public int pauseAll() {
        if (registry == null) {
            return 0;
        }
        int count = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isContainerPaused() && !container.isPauseRequested()) {
                container.pause();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-PAUSE-ALL] 成功暂停所有消费监听容器: totalPaused={}", count);
        return count;
    }

    /**
     * 恢复当前服务所有已暂停的消费监听容器
     *
     * @return 本次成功恢复的容器数量
     */
    public int resumeAll() {
        if (registry == null) {
            return 0;
        }
        int count = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isContainerPaused() || container.isPauseRequested()) {
                container.resume();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-RESUME-ALL] 成功恢复所有消费监听容器: totalResumed={}", count);
        return count;
    }

    /**
     * 停止当前服务所有消费监听容器
     *
     * @return 本次成功停止的容器数量
     */
    public int stopAll() {
        if (registry == null) {
            return 0;
        }
        int count = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                container.stop();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-STOP-ALL] 成功停止所有消费监听容器: totalStopped={}", count);
        return count;
    }

    /**
     * 启动当前服务所有已停止的消费监听容器
     *
     * @return 本次成功启动的容器数量
     */
    public int startAll() {
        if (registry == null) {
            return 0;
        }
        int count = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
                count++;
            }
        }
        log.info("[MQ-CONTAINER-START-ALL] 成功启动所有消费监听容器: totalStarted={}", count);
        return count;
    }

    // =========================================================================
    // 3. 容器探测与检索 (Container Lookup)
    // =========================================================================

    /**
     * 根据监听端点 ID 获取原生容器实例
     *
     * @param id 监听端点 ID
     * @return 原生容器实例，未找到返回 null
     */
    public MessageListenerContainer getListenerContainer(String id) {
        if (!StringUtils.hasText(id) || registry == null) {
            return null;
        }
        return registry.getListenerContainer(id);
    }

    /**
     * 获取所有已注册监听端点的 ID 集合
     *
     * @return 端点 ID 集合
     */
    public Set<String> getListenerContainerIds() {
        if (registry == null) {
            return Collections.emptySet();
        }
        Set<String> ids = registry.getListenerContainerIds();
        return ids != null ? Collections.unmodifiableSet(ids) : Collections.emptySet();
    }

    /**
     * 智能解析并查找目标监听容器集合（优先按 ID 匹配，未命中则按订阅主题匹配）
     *
     * @param idOrTopic 端点 ID 或订阅主题名称
     * @return 匹配的容器列表（不可变列表）
     */
    public List<MessageListenerContainer> findContainers(String idOrTopic) {
        if (!StringUtils.hasText(idOrTopic) || registry == null) {
            return Collections.emptyList();
        }

        // 1. 优先按 Listener ID 精确匹配
        MessageListenerContainer directContainer = registry.getListenerContainer(idOrTopic);
        if (directContainer != null) {
            return List.of(directContainer);
        }

        // 2. 次选按订阅 Topic 匹配（精确 Topic 名称、Topic 正则表达式、指定分区分配）
        List<MessageListenerContainer> matched = new ArrayList<>();
        Collection<MessageListenerContainer> allContainers = registry.getListenerContainers();
        if (allContainers != null) {
            for (MessageListenerContainer container : allContainers) {
                if (matchesTopic(container, idOrTopic)) {
                    matched.add(container);
                }
            }
        }
        return Collections.unmodifiableList(matched);
    }

    private boolean matchesTopic(MessageListenerContainer container, String topic) {
        ContainerProperties props = container.getContainerProperties();
        if (props == null) {
            return false;
        }
        // 精确匹配主题列表
        if (props.getTopics() != null) {
            for (String t : props.getTopics()) {
                if (topic.equals(t)) {
                    return true;
                }
            }
        }
        // 正则表达式匹配
        if (props.getTopicPattern() != null && props.getTopicPattern().matcher(topic).matches()) {
            return true;
        }
        // 分区分配匹配
        if (props.getTopicPartitions() != null) {
            for (TopicPartitionOffset tpo : props.getTopicPartitions()) {
                if (topic.equals(tpo.getTopic())) {
                    return true;
                }
            }
        }
        return false;
    }
}
