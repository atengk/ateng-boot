package io.github.atengk.boot.kafka.manager;

import io.github.atengk.boot.kafka.model.KafkaContainerStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.Lifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ReentrantLock scaleLock = new ReentrantLock();

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

    /**
     * 运行期动态调整目标监听容器并发消费者数量
     *
     * <p>对处于运行态的 {@link ConcurrentMessageListenerContainer}，先安全停止再更新并发数并重启（保持原暂停状态），
     * 确保新增或缩减的子消费容器立即生效。</p>
     *
     * @param idOrTopic   监听容器 ID 或订阅主题名称
     * @param concurrency 目标并发线程数（必须大于 0）
     * @return 匹配并完成并发调整返回 {@code true}；未匹配任何容器返回 {@code false}
     * @throws IllegalArgumentException {@code idOrTopic} 为空白或 {@code concurrency <= 0} 时抛出
     * @throws IllegalStateException    匹配容器非 {@link ConcurrentMessageListenerContainer} 时抛出
     */
    public boolean scaleConcurrency(String idOrTopic, int concurrency) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        Assert.isTrue(concurrency > 0, "concurrency 必须大于 0");

        List<MessageListenerContainer> targets = findContainers(idOrTopic);
        if (targets.isEmpty()) {
            log.warn("[MQ-CONTAINER-SCALE] 未找到匹配的消费监听容器: idOrTopic={}, concurrency={}", idOrTopic, concurrency);
            return false;
        }

        // 先校验所有目标容器类型，避免部分容器已变更后才失败
        List<ConcurrentMessageListenerContainer<?, ?>> concurrentContainers = new ArrayList<>(targets.size());
        for (MessageListenerContainer container : targets) {
            if (!(container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent)) {
                throw new IllegalStateException(
                        "容器类型不支持动态调整并发度 (仅支持 ConcurrentMessageListenerContainer): "
                                + container.getClass().getName());
            }
            concurrentContainers.add(concurrent);
        }

        scaleLock.lock();
        try {
            for (ConcurrentMessageListenerContainer<?, ?> concurrent : concurrentContainers) {
                boolean wasRunning = concurrent.isRunning();
                boolean wasPaused = concurrent.isContainerPaused() || concurrent.isPauseRequested();
                if (wasRunning) {
                    concurrent.stop();
                }
                concurrent.setConcurrency(concurrency);
                if (wasRunning) {
                    if (wasPaused) {
                        // 在 start 前先标记暂停，避免子容器启动后产生拉取窗口
                        concurrent.pause();
                    }
                    concurrent.start();
                }
                log.info("[MQ-CONTAINER-SCALE] 动态调整消费容器并发度完成: idOrTopic={}, listenerId={}, concurrency={}",
                        idOrTopic, concurrent.getListenerId(), concurrency);
            }
        } finally {
            scaleLock.unlock();
        }
        return true;
    }

    // =========================================================================
    // 3. 容器探测、状态快照与检索 (Container Lookup & Status Snapshots)
    // =========================================================================

    /**
     * 获取全部已注册消费监听容器的不可变状态快照列表
     *
     * @return 容器状态快照不可变列表（无注册表或无容器时保底返回空不可变列表）
     */
    public List<KafkaContainerStatus> getContainerStatuses() {
        if (registry == null) {
            return List.of();
        }
        List<KafkaContainerStatus> statuses = new ArrayList<>();
        Set<String> ids = registry.getListenerContainerIds();
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                MessageListenerContainer container = registry.getListenerContainer(id);
                if (container != null) {
                    statuses.add(toContainerStatus(id, container));
                }
            }
            return Collections.unmodifiableList(statuses);
        }

        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers != null) {
            for (MessageListenerContainer container : containers) {
                if (container != null) {
                    statuses.add(toContainerStatus(container.getListenerId(), container));
                }
            }
        }
        return Collections.unmodifiableList(statuses);
    }

    /**
     * 查询指定监听容器 ID 或订阅主题对应的运行状态快照
     *
     * @param idOrTopic 监听容器 ID 或订阅主题名称
     * @return 首个匹配容器的状态快照，未命中任何容器时返回 {@code null}
     * @throws IllegalArgumentException {@code idOrTopic} 为空白时抛出
     */
    public KafkaContainerStatus getContainerStatus(String idOrTopic) {
        Assert.hasText(idOrTopic, "idOrTopic 不能为空白");
        String target = idOrTopic.trim();
        List<MessageListenerContainer> matched = findContainers(target);
        if (matched.isEmpty()) {
            return null;
        }
        MessageListenerContainer first = matched.get(0);
        return toContainerStatus(resolveRegisteredId(first, target), first);
    }

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
        String target = idOrTopic.trim();

        // 1. 优先按 Listener ID 精确匹配
        MessageListenerContainer directContainer = registry.getListenerContainer(target);
        if (directContainer != null) {
            return List.of(directContainer);
        }

        // 2. 次选按订阅 Topic 匹配（精确 Topic 名称、Topic 正则表达式、指定分区分配）
        List<MessageListenerContainer> matched = new ArrayList<>();
        Collection<MessageListenerContainer> allContainers = registry.getListenerContainers();
        if (allContainers != null) {
            for (MessageListenerContainer container : allContainers) {
                if (matchesTopic(container, target)) {
                    matched.add(container);
                }
            }
        }
        return Collections.unmodifiableList(matched);
    }

    private KafkaContainerStatus toContainerStatus(String registeredId, MessageListenerContainer container) {
        String listenerId = StringUtils.hasText(container.getListenerId())
                ? container.getListenerId()
                : registeredId;
        ContainerProperties props = container.getContainerProperties();
        String groupId = StringUtils.hasText(container.getGroupId())
                ? container.getGroupId()
                : (props != null ? props.getGroupId() : null);

        List<String> topics = extractTopics(props);
        boolean running = container.isRunning();
        boolean paused = container.isContainerPaused() || container.isPauseRequested();
        boolean autoStartup = container.isAutoStartup();
        boolean batchListener = isBatchListenerContainer(props);

        int concurrency = 0;
        int activeContainerCount = running ? 1 : 0;
        if (container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
            concurrency = concurrent.getConcurrency();
            List<?> children = concurrent.getContainers();
            if (children != null) {
                activeContainerCount = (int) children.stream()
                        .filter(child -> child instanceof Lifecycle lifecycle && lifecycle.isRunning())
                        .count();
            } else {
                activeContainerCount = 0;
            }
        } else if (container instanceof KafkaMessageListenerContainer<?, ?>) {
            concurrency = 1;
        }

        Collection<TopicPartition> rawPartitions = container.getAssignedPartitions();
        List<String> assignedPartitions = rawPartitions == null
                ? List.of()
                : rawPartitions.stream()
                        .filter(Objects::nonNull)
                        .map(TopicPartition::toString)
                        .sorted()
                        .toList();

        return new KafkaContainerStatus(
                listenerId,
                groupId,
                topics,
                running,
                paused,
                autoStartup,
                batchListener,
                concurrency,
                activeContainerCount,
                assignedPartitions,
                container.getClass().getSimpleName()
        );
    }

    private String resolveRegisteredId(MessageListenerContainer targetContainer, String fallback) {
        if (StringUtils.hasText(targetContainer.getListenerId())) {
            return targetContainer.getListenerId();
        }
        if (registry != null && registry.getListenerContainerIds() != null) {
            for (String id : registry.getListenerContainerIds()) {
                if (registry.getListenerContainer(id) == targetContainer) {
                    return id;
                }
            }
        }
        return fallback;
    }

    private List<String> extractTopics(ContainerProperties props) {
        if (props == null) {
            return List.of();
        }
        Set<String> resolved = new LinkedHashSet<>();
        if (props.getTopics() != null) {
            for (String topic : props.getTopics()) {
                if (StringUtils.hasText(topic)) {
                    resolved.add(topic);
                }
            }
        }
        if (props.getTopicPattern() != null) {
            resolved.add("pattern:" + props.getTopicPattern().pattern());
        }
        if (props.getTopicPartitions() != null) {
            for (TopicPartitionOffset tpo : props.getTopicPartitions()) {
                if (tpo != null && StringUtils.hasText(tpo.getTopic())) {
                    resolved.add(tpo.getTopic());
                }
            }
        }
        return List.copyOf(resolved);
    }

    private boolean isBatchListenerContainer(ContainerProperties props) {
        if (props == null) {
            return false;
        }
        return props.getMessageListener() instanceof BatchMessageListener<?, ?>;
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
