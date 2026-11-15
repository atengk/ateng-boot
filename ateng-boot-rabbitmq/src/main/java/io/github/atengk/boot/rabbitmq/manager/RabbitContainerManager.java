package io.github.atengk.boot.rabbitmq.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * RabbitMQ 监听容器运行期管控与观测门面
 * 封装按容器 ID / 队列名精准启停、全量启停、动态并发扩缩容、预取数调节以及容器与队列深度观测能力
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Slf4j
public class RabbitContainerManager {

    private static final Method GET_PREFETCH_COUNT_METHOD = resolvePrefetchGetter();

    private final Supplier<RabbitListenerEndpointRegistry> registrySupplier;
    private final Supplier<RabbitAdmin> rabbitAdminSupplier;

    /**
     * 通过 Spring 延迟提供者构造容器管控门面
     *
     * @param registryProvider    监听容器注册中心提供者
     * @param rabbitAdminProvider RabbitAdmin 管理客户端提供者 (可选)
     */
    @Autowired
    public RabbitContainerManager(
            ObjectProvider<RabbitListenerEndpointRegistry> registryProvider,
            ObjectProvider<RabbitAdmin> rabbitAdminProvider) {
        this.registrySupplier = registryProvider == null ? () -> null : registryProvider::getIfAvailable;
        this.rabbitAdminSupplier = rabbitAdminProvider == null ? () -> null : rabbitAdminProvider::getIfAvailable;
    }

    /**
     * 直接传入监听容器注册中心构造管控门面 (无队列深度查询能力)
     *
     * @param registry 监听容器注册中心
     */
    public RabbitContainerManager(RabbitListenerEndpointRegistry registry) {
        this(registry, null);
    }

    /**
     * 直接传入监听容器注册中心与管理客户端构造管控门面
     *
     * @param registry    监听容器注册中心
     * @param rabbitAdmin RabbitAdmin 管理客户端 (可为 null)
     */
    public RabbitContainerManager(RabbitListenerEndpointRegistry registry, RabbitAdmin rabbitAdmin) {
        this.registrySupplier = () -> registry;
        this.rabbitAdminSupplier = () -> rabbitAdmin;
    }

    /**
     * 根据精确容器 ID 获取监听容器实例
     *
     * @param id 监听容器 ID
     * @return 匹配的容器实例，未找到返回 null
     */
    public MessageListenerContainer getListenerContainer(String id) {
        Assert.hasText(id, "id 不能为空");
        RabbitListenerEndpointRegistry registry = getRegistry();
        return registry == null ? null : registry.getListenerContainer(id.trim());
    }

    /**
     * 获取当前已注册的全部监听容器 ID 集合
     *
     * @return 不可变容器 ID 集合
     */
    public Set<String> getListenerContainerIds() {
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry == null) {
            return Collections.emptySet();
        }
        Set<String> ids = registry.getListenerContainerIds();
        return ids == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    /**
     * 按容器 ID 或队列名称查找全部匹配的监听容器 (支持多容器共享同一队列场景)
     *
     * @param idOrQueue 容器 ID 或队列名称
     * @return 匹配的不可变容器列表，未命中返回空集合
     */
    public List<MessageListenerContainer> findContainers(String idOrQueue) {
        Assert.hasText(idOrQueue, "idOrQueue 不能为空");
        String target = idOrQueue.trim();
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry == null) {
            return Collections.emptyList();
        }
        MessageListenerContainer exact = registry.getListenerContainer(target);
        if (exact != null) {
            return List.of(exact);
        }
        List<MessageListenerContainer> matched = new ArrayList<>();
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (matchesQueue(container, target)) {
                matched.add(container);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /**
     * 停止指定监听容器 ID 或监听目标队列的全部容器
     *
     * @param idOrQueue 容器 ID 或队列名称
     */
    public void stop(String idOrQueue) {
        List<MessageListenerContainer> targets = resolveRequiredContainers(idOrQueue);
        for (MessageListenerContainer container : targets) {
            if (container.isRunning()) {
                container.stop();
                log.info("[MQ-CONTAINER-STOP] 已停止 RabbitMQ 监听容器: target={}, listenerId={}",
                        idOrQueue, resolveContainerId(container, idOrQueue));
            }
        }
    }

    /**
     * 启动指定监听容器 ID 或监听目标队列的全部容器
     *
     * @param idOrQueue 容器 ID 或队列名称
     */
    public void start(String idOrQueue) {
        List<MessageListenerContainer> targets = resolveRequiredContainers(idOrQueue);
        for (MessageListenerContainer container : targets) {
            if (!container.isRunning()) {
                container.start();
                log.info("[MQ-CONTAINER-START] 已启动 RabbitMQ 监听容器: target={}, listenerId={}",
                        idOrQueue, resolveContainerId(container, idOrQueue));
            }
        }
    }

    /**
     * 判断指定容器或队列对应的监听容器是否处于运行状态
     *
     * @param idOrQueue 容器 ID 或队列名称
     * @return 任一匹配容器运行中返回 true，否则返回 false
     */
    public boolean isRunning(String idOrQueue) {
        List<MessageListenerContainer> targets = resolveRequiredContainers(idOrQueue);
        return targets.stream().anyMatch(MessageListenerContainer::isRunning);
    }

    /**
     * 停止注册中心内的全部监听容器
     */
    public void stopAll() {
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        int stopped = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                container.stop();
                stopped++;
            }
        }
        log.info("[MQ-CONTAINER-STOP-ALL] 已触发全量 RabbitMQ 监听容器停止: count={}", stopped);
    }

    /**
     * 启动注册中心内的全部监听容器
     */
    public void startAll() {
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        int started = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                container.start();
                started++;
            }
        }
        log.info("[MQ-CONTAINER-START-ALL] 已触发全量 RabbitMQ 监听容器启动: count={}", started);
    }

    /**
     * 运行期动态调整目标容器并发消费者数上下限
     *
     * @param idOrQueue              容器 ID 或队列名称
     * @param concurrentConsumers    最小并发消费者数 (必须大于 0)
     * @param maxConcurrentConsumers 最大并发消费者数 (必须大于等于 concurrentConsumers)
     */
    public void scaleConcurrency(String idOrQueue, int concurrentConsumers, int maxConcurrentConsumers) {
        Assert.isTrue(concurrentConsumers > 0, "concurrentConsumers 必须大于 0");
        Assert.isTrue(maxConcurrentConsumers > 0, "maxConcurrentConsumers 必须大于 0");
        Assert.isTrue(maxConcurrentConsumers >= concurrentConsumers,
                "maxConcurrentConsumers 不能小于 concurrentConsumers");

        List<MessageListenerContainer> targets = resolveRequiredContainers(idOrQueue);
        List<SimpleMessageListenerContainer> simpleContainers = new ArrayList<>(targets.size());
        for (MessageListenerContainer container : targets) {
            if (!(container instanceof SimpleMessageListenerContainer simple)) {
                throw new IllegalStateException("容器类型不支持并发消费者扩缩容 (仅支持 SimpleMessageListenerContainer): "
                        + container.getClass().getName());
            }
            simpleContainers.add(simple);
        }

        String concurrencyRange = concurrentConsumers + "-" + maxConcurrentConsumers;
        for (SimpleMessageListenerContainer simple : simpleContainers) {
            simple.setConcurrency(concurrencyRange);
            log.info("[MQ-CONTAINER-SCALE] 动态调整监听容器并发数: target={}, listenerId={}, concurrency={}",
                    idOrQueue, resolveContainerId(simple, idOrQueue), concurrencyRange);
        }
    }

    /**
     * 运行期动态调整目标容器通道预取消息数 (prefetchCount)
     *
     * @param idOrQueue     容器 ID 或队列名称
     * @param prefetchCount 预取消息数量 (AMQP 0-9-1 协议范围 [1, 65535])
     */
    public void updatePrefetchCount(String idOrQueue, int prefetchCount) {
        Assert.isTrue(prefetchCount > 0 && prefetchCount <= 65535, "prefetchCount 必须在 [1, 65535] 范围内");

        List<MessageListenerContainer> targets = resolveRequiredContainers(idOrQueue);
        List<AbstractMessageListenerContainer> abstractContainers = new ArrayList<>(targets.size());
        for (MessageListenerContainer container : targets) {
            if (!(container instanceof AbstractMessageListenerContainer abstractContainer)) {
                throw new IllegalStateException("容器类型不支持调整预取数 (需继承 AbstractMessageListenerContainer): "
                        + container.getClass().getName());
            }
            abstractContainers.add(abstractContainer);
        }

        for (AbstractMessageListenerContainer abstractContainer : abstractContainers) {
            abstractContainer.setPrefetchCount(prefetchCount);
            log.info("[MQ-CONTAINER-PREFETCH] 动态调整监听容器预取数: target={}, listenerId={}, prefetchCount={}",
                    idOrQueue, resolveContainerId(abstractContainer, idOrQueue), prefetchCount);
        }
    }

    /**
     * 查询全部已注册监听容器的状态快照列表
     *
     * @return 不可变容器状态快照列表
     */
    public List<RabbitContainerStatus> getContainerStatuses() {
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry == null) {
            return Collections.emptyList();
        }
        List<RabbitContainerStatus> statuses = new ArrayList<>();
        for (String id : registry.getListenerContainerIds()) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            if (container != null) {
                statuses.add(toContainerStatus(id, container));
            }
        }
        return Collections.unmodifiableList(statuses);
    }

    /**
     * 查询指定容器 ID 或队列对应的监听容器状态快照 (同队列多容器场景下返回首个匹配的监听容器状态)
     *
     * @param idOrQueue 容器 ID 或队列名称
     * @return 监听容器状态快照
     */
    public RabbitContainerStatus getContainerStatus(String idOrQueue) {
        Assert.hasText(idOrQueue, "idOrQueue 不能为空");
        String target = idOrQueue.trim();
        RabbitListenerEndpointRegistry registry = getRegistry();
        if (registry != null) {
            MessageListenerContainer exact = registry.getListenerContainer(target);
            if (exact != null) {
                return toContainerStatus(target, exact);
            }
            for (String id : registry.getListenerContainerIds()) {
                MessageListenerContainer container = registry.getListenerContainer(id);
                if (container != null && matchesQueue(container, target)) {
                    return toContainerStatus(id, container);
                }
            }
        }
        throw new IllegalArgumentException("未找到匹配的 RabbitMQ 监听容器: " + idOrQueue);
    }

    /**
     * 查询指定队列当前堆积深度与在线消费者数量 (RabbitAdmin 不可用或队列不存在时安全降级返回零值与 exists=false)
     *
     * @param queueName 队列名称
     * @return 队列深度观测快照
     */
    public RabbitQueueStatus getQueueStatus(String queueName) {
        Assert.hasText(queueName, "queueName 不能为空");
        String normalizedQueue = queueName.trim();
        RabbitAdmin rabbitAdmin = rabbitAdminSupplier.get();
        if (rabbitAdmin == null) {
            log.debug("[MQ-QUEUE-STATUS] RabbitAdmin 不可用，降级返回不存在零值快照: queue={}", normalizedQueue);
            return new RabbitQueueStatus(normalizedQueue, 0L, 0, false);
        }
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(normalizedQueue);
            if (info == null) {
                return new RabbitQueueStatus(normalizedQueue, 0L, 0, false);
            }
            return new RabbitQueueStatus(normalizedQueue, info.getMessageCount(), info.getConsumerCount(), true);
        } catch (RuntimeException ex) {
            log.warn("[MQ-QUEUE-STATUS] 查询队列状态异常，安全降级返回零值快照: queue={}, reason={}",
                    normalizedQueue, ex.getMessage());
            return new RabbitQueueStatus(normalizedQueue, 0L, 0, false);
        }
    }

    private RabbitListenerEndpointRegistry getRegistry() {
        return registrySupplier.get();
    }

    private List<MessageListenerContainer> resolveRequiredContainers(String idOrQueue) {
        List<MessageListenerContainer> targets = findContainers(idOrQueue);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("未找到匹配的 RabbitMQ 监听容器: " + idOrQueue);
        }
        return targets;
    }

    private boolean matchesQueue(MessageListenerContainer container, String queueName) {
        if (container instanceof AbstractMessageListenerContainer abstractContainer) {
            String[] queues = abstractContainer.getQueueNames();
            if (queues != null) {
                for (String queue : queues) {
                    if (queueName.equals(queue)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private RabbitContainerStatus toContainerStatus(String registeredId, MessageListenerContainer container) {
        String listenerId = resolveContainerId(container, registeredId);
        List<String> queueNames = Collections.emptyList();
        int prefetchCount = 0;
        if (container instanceof AbstractMessageListenerContainer abstractContainer) {
            String[] rawQueues = abstractContainer.getQueueNames();
            queueNames = rawQueues == null ? Collections.emptyList() : Arrays.asList(rawQueues);
            prefetchCount = extractPrefetchCount(abstractContainer);
        }
        int activeConsumers = container instanceof SimpleMessageListenerContainer simple
                ? simple.getActiveConsumerCount()
                : 0;
        return new RabbitContainerStatus(
                listenerId,
                queueNames,
                container.isRunning(),
                container.isAutoStartup(),
                activeConsumers,
                prefetchCount,
                container.getClass().getSimpleName()
        );
    }

    private String resolveContainerId(MessageListenerContainer container, String fallback) {
        if (container instanceof AbstractMessageListenerContainer abstractContainer) {
            String id = abstractContainer.getListenerId();
            if (StringUtils.hasText(id)) {
                return id;
            }
        }
        return fallback;
    }

    private int extractPrefetchCount(AbstractMessageListenerContainer container) {
        if (GET_PREFETCH_COUNT_METHOD == null) {
            return AbstractMessageListenerContainer.DEFAULT_PREFETCH_COUNT;
        }
        Object value = ReflectionUtils.invokeMethod(GET_PREFETCH_COUNT_METHOD, container);
        return value instanceof Integer count ? count : AbstractMessageListenerContainer.DEFAULT_PREFETCH_COUNT;
    }

    private static Method resolvePrefetchGetter() {
        try {
            Method method = ReflectionUtils.findMethod(AbstractMessageListenerContainer.class, "getPrefetchCount");
            if (method != null) {
                ReflectionUtils.makeAccessible(method);
            }
            return method;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
