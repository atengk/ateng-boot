package io.github.atengk.boot.admin.eventstore;

import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.eventstore.ConcurrentMapEventStore;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 具备有界历史淘汰与主动驱逐能力的内存事件仓库
 * <p>
 * 防止容器云频繁扩缩容产生大量已注销实例 {@link InstanceEvent} 导致堆内存无限增长；
 * 支持在超出 {@code maxRetainedDeregistered} 阈值时按 FIFO 顺序淘汰最旧已注销实例事件，
 * 并供 {@code ZombieInstanceCleanupScheduler} 在驱逐僵尸实例后立即释放残留事件历史。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AtengInMemoryEventStore extends ConcurrentMapEventStore {

    /**
     * 默认单个实例最多保留事件日志条数
     */
    public static final int DEFAULT_MAX_LOG_SIZE_PER_AGGREGATE = 100;

    /**
     * 默认最多保留已注销实例的历史事件数
     */
    public static final int DEFAULT_MAX_RETAINED_DEREGISTERED = 1000;

    private final ConcurrentMap<InstanceId, List<InstanceEvent>> eventLogMap;
    private final int maxRetainedDeregistered;
    private final ReentrantLock retentionLock = new ReentrantLock();
    private final Deque<InstanceId> deregisteredOrder = new ArrayDeque<>();
    private final Set<InstanceId> deregisteredSet = new HashSet<>();

    public AtengInMemoryEventStore() {
        this(DEFAULT_MAX_LOG_SIZE_PER_AGGREGATE, DEFAULT_MAX_RETAINED_DEREGISTERED);
    }

    public AtengInMemoryEventStore(int maxLogSizePerAggregate, int maxRetainedDeregistered) {
        this(maxLogSizePerAggregate, maxRetainedDeregistered, new ConcurrentHashMap<>());
    }

    private AtengInMemoryEventStore(int maxLogSizePerAggregate,
                                    int maxRetainedDeregistered,
                                    ConcurrentMap<InstanceId, List<InstanceEvent>> backingMap) {
        super(maxLogSizePerAggregate > 0 ? maxLogSizePerAggregate : DEFAULT_MAX_LOG_SIZE_PER_AGGREGATE, backingMap);
        this.eventLogMap = backingMap;
        this.maxRetainedDeregistered = maxRetainedDeregistered;
    }

    @Override
    public Mono<Void> append(List<InstanceEvent> events) {
        return super.append(events)
                .then(Mono.fromRunnable(() -> {
                    publish(events);
                    trackAndTrimDeregisteredEvents(events);
                }));
    }

    /**
     * 主动驱逐指定实例的全部事件历史记录并释放内存
     *
     * @param instanceId 实例唯一标识
     */
    public void evict(InstanceId instanceId) {
        if (instanceId == null) {
            return;
        }
        retentionLock.lock();
        try {
            if (deregisteredSet.remove(instanceId)) {
                deregisteredOrder.remove(instanceId);
            }
            eventLogMap.remove(instanceId);
        } finally {
            retentionLock.unlock();
        }
    }

    /**
     * 获取当前内存中仍驻留事件日志的实例数量（用于观测与容量测试）
     *
     * @return 当前存储的实例个数
     */
    public int getStoredInstanceCount() {
        return eventLogMap.size();
    }

    private void trackAndTrimDeregisteredEvents(List<InstanceEvent> events) {
        if (events == null || events.isEmpty() || maxRetainedDeregistered < 0) {
            return;
        }
        retentionLock.lock();
        try {
            for (InstanceEvent event : events) {
                if (event == null || event.getInstance() == null) {
                    continue;
                }
                InstanceId id = event.getInstance();
                if (event instanceof InstanceRegisteredEvent) {
                    if (deregisteredSet.remove(id)) {
                        deregisteredOrder.remove(id);
                    }
                } else if (event instanceof InstanceDeregisteredEvent) {
                    if (deregisteredSet.contains(id)) {
                        deregisteredOrder.remove(id);
                    } else {
                        deregisteredSet.add(id);
                    }
                    deregisteredOrder.addLast(id);
                    while (deregisteredOrder.size() > maxRetainedDeregistered) {
                        InstanceId oldest = deregisteredOrder.pollFirst();
                        if (oldest != null) {
                            deregisteredSet.remove(oldest);
                            eventLogMap.remove(oldest);
                            log.debug("[EventStore] 超出已注销历史实例保留上限 [{}]，已淘汰最旧实例事件: [{}]",
                                    maxRetainedDeregistered, oldest);
                        }
                    }
                }
            }
        } finally {
            retentionLock.unlock();
        }
    }
}
