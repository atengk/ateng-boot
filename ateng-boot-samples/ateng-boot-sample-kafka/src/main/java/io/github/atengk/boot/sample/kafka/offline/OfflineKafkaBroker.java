package io.github.atengk.boot.sample.kafka.offline;

import org.apache.kafka.clients.admin.DeletedRecords;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Utils;
import org.springframework.util.Assert;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Kafka 离线自洽内存模拟 Broker，用于在无真实 Kafka 集群环境（非 local Profile）下支撑主题注册、分区日志 append/truncate、消费组 Rebalance、位移提交/回溯及原子事务发布语义
 *
 * @author Ateng
 * @since 2026-11-15
 */
public class OfflineKafkaBroker {

    private static final Node LOCAL_NODE = new Node(0, "127.0.0.1", 9092);
    private static final String CLUSTER_ID = "offline-kafka-cluster";
    private static final int DEFAULT_MAX_POLL_RECORDS = 500;
    private static final Method TIMESTAMP_SPEC_METHOD = resolveTimestampSpecMethod();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition dataAvailable = lock.newCondition();

    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, Map<TopicPartition, OffsetAndMetadata>> committedOffsets = new ConcurrentHashMap<>();
    private final Map<String, ConsumerSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final ThreadLocal<List<PendingPublish>> pendingTxRecords = new ThreadLocal<>();

    /**
     * 声明并注册主题元数据（幂等，扩容分区数时向上对齐）
     *
     * @param newTopics 待注册主题定义集合
     */
    public void declareTopics(Collection<NewTopic> newTopics) {
        if (newTopics == null || newTopics.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            for (NewTopic newTopic : newTopics) {
                if (newTopic == null || newTopic.name() == null || newTopic.name().isBlank()) {
                    continue;
                }
                int partitions = Math.max(1, newTopic.numPartitions());
                TopicState state = topics.get(newTopic.name());
                if (state == null) {
                    topics.put(newTopic.name(), new TopicState(newTopic.name(), partitions));
                } else if (partitions > state.partitions.size()) {
                    state.expandPartitions(partitions);
                }
            }
            markSubscribedGroupsForRebalanceLocked();
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取指定主题分区元数据列表（主题不存在时返回空列表）
     *
     * @param topic 主题名称
     * @return 分区信息列表
     */
    public List<PartitionInfo> partitionsFor(String topic) {
        if (topic == null || topic.isBlank()) {
            return Collections.emptyList();
        }
        lock.lock();
        try {
            TopicState state = topics.get(topic);
            if (state == null) {
                return Collections.emptyList();
            }
            return buildPartitionInfos(state);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 列出全量已知主题及分区元数据
     *
     * @return 主题到分区信息映射
     */
    public Map<String, List<PartitionInfo>> listAllTopics() {
        lock.lock();
        try {
            Map<String, List<PartitionInfo>> result = new LinkedHashMap<>();
            for (TopicState state : topics.values()) {
                result.put(state.name, buildPartitionInfos(state));
            }
            return Collections.unmodifiableMap(result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 描述指定主题集合
     *
     * @param topicNames 主题名称集合
     * @return 每个主题对应的异步描述结果
     */
    public Map<String, KafkaFuture<TopicDescription>> describeTopics(Collection<String> topicNames) {
        lock.lock();
        try {
            Map<String, KafkaFuture<TopicDescription>> futures = new LinkedHashMap<>();
            if (topicNames == null) {
                return futures;
            }
            for (String topicName : topicNames) {
                KafkaFutureImpl<TopicDescription> future = new KafkaFutureImpl<>();
                TopicState state = topics.get(topicName);
                if (state == null) {
                    future.completeExceptionally(new UnknownTopicOrPartitionException("主题不存在: " + topicName));
                } else {
                    List<TopicPartitionInfo> tpInfos = new ArrayList<>(state.partitions.size());
                    for (int p = 0; p < state.partitions.size(); p++) {
                        tpInfos.add(new TopicPartitionInfo(
                                p,
                                LOCAL_NODE,
                                List.of(LOCAL_NODE),
                                List.of(LOCAL_NODE)));
                    }
                    future.complete(new TopicDescription(topicName, false, tpInfos));
                }
                futures.put(topicName, future);
            }
            return futures;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 开启当前线程的离线事务缓冲上下文
     */
    public void beginTransaction() {
        pendingTxRecords.set(new ArrayList<>());
    }

    /**
     * 提交当前线程事务缓冲中的所有待发布记录，使其原子对消费者可见
     */
    public void commitTransaction() {
        List<PendingPublish> batch = pendingTxRecords.get();
        pendingTxRecords.remove();
        if (batch == null || batch.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            for (PendingPublish item : batch) {
                appendRecordLocked(
                        item.topic,
                        item.partition,
                        item.timestamp,
                        item.keyBytes,
                        item.valueBytes,
                        item.headers);
            }
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 回滚并丢弃当前线程事务缓冲中的所有待发布记录
     */
    public void rollbackTransaction() {
        pendingTxRecords.remove();
    }

    /**
     * 发布单条记录至指定主题分区（支持隐式按需建主题与事务缓冲）
     *
     * @param topic 主题名称
     * @param partition 目标分区（null 时按键哈希或轮询分配）
     * @param timestamp 消息时间戳（null 时取当前系统毫秒数）
     * @param keyBytes 键字节数组（可为 null）
     * @param valueBytes 值字节数组（可为 null）
     * @param headers 消息头集合（可为 null）
     * @return 写入后的分区元数据
     */
    public RecordMetadata publish(
            String topic,
            Integer partition,
            Long timestamp,
            byte[] keyBytes,
            byte[] valueBytes,
            Headers headers) {
        Assert.hasText(topic, "topic 不能为空白");
        long ts = timestamp != null ? timestamp : System.currentTimeMillis();
        byte[] keyCopy = keyBytes != null ? Arrays.copyOf(keyBytes, keyBytes.length) : null;
        byte[] valueCopy = valueBytes != null ? Arrays.copyOf(valueBytes, valueBytes.length) : null;
        RecordHeaders headerCopy = copyHeaders(headers);

        List<PendingPublish> txBatch = pendingTxRecords.get();
        if (txBatch != null) {
            txBatch.add(new PendingPublish(topic, partition, ts, keyCopy, valueCopy, headerCopy));
            int predictedPartition = partition != null ? partition : 0;
            return new RecordMetadata(
                    new TopicPartition(topic, predictedPartition),
                    -1L,
                    0,
                    ts,
                    keyCopy != null ? keyCopy.length : -1,
                    valueCopy != null ? valueCopy.length : -1);
        }

        lock.lock();
        try {
            RecordMetadata metadata = appendRecordLocked(topic, partition, ts, keyCopy, valueCopy, headerCopy);
            dataAvailable.signalAll();
            return metadata;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查询分区在指定位移规格（Earliest/Latest/Timestamp）下的偏移量
     *
     * @param specs 分区与位移查询规格映射
     * @return 分区位移异步结果映射
     */
    public Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> listOffsets(
            Map<TopicPartition, OffsetSpec> specs) {
        lock.lock();
        try {
            Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> result = new LinkedHashMap<>();
            if (specs == null) {
                return result;
            }
            for (Map.Entry<TopicPartition, OffsetSpec> entry : specs.entrySet()) {
                TopicPartition tp = entry.getKey();
                OffsetSpec spec = entry.getValue();
                KafkaFutureImpl<ListOffsetsResult.ListOffsetsResultInfo> future = new KafkaFutureImpl<>();
                PartitionState partition = findPartitionLocked(tp);
                if (partition == null) {
                    future.complete(new ListOffsetsResult.ListOffsetsResultInfo(0L, -1L, Optional.empty()));
                } else if (spec instanceof OffsetSpec.EarliestSpec) {
                    future.complete(new ListOffsetsResult.ListOffsetsResultInfo(partition.lowWatermark, -1L, Optional.empty()));
                } else if (spec instanceof OffsetSpec.LatestSpec) {
                    future.complete(new ListOffsetsResult.ListOffsetsResultInfo(partition.records.size(), -1L, Optional.empty()));
                } else if (spec instanceof OffsetSpec.TimestampSpec tsSpec) {
                    long targetTs = extractTimestamp(tsSpec);
                    ListOffsetsResult.ListOffsetsResultInfo matched = findOffsetForTimestampLocked(partition, targetTs);
                    future.complete(matched != null
                            ? matched
                            : new ListOffsetsResult.ListOffsetsResultInfo(-1L, -1L, Optional.empty()));
                } else {
                    future.complete(new ListOffsetsResult.ListOffsetsResultInfo(partition.records.size(), -1L, Optional.empty()));
                }
                result.put(tp, future);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按时间戳查询最匹配的位移条目
     *
     * @param timestamps 分区到目标时间戳映射
     * @return 命中的分区位移与时间戳映射
     */
    public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestamps) {
        lock.lock();
        try {
            Map<TopicPartition, OffsetAndTimestamp> result = new LinkedHashMap<>();
            if (timestamps == null) {
                return result;
            }
            for (Map.Entry<TopicPartition, Long> entry : timestamps.entrySet()) {
                PartitionState partition = findPartitionLocked(entry.getKey());
                long targetTs = entry.getValue() != null ? entry.getValue() : 0L;
                ListOffsetsResult.ListOffsetsResultInfo info = partition != null
                        ? findOffsetForTimestampLocked(partition, targetTs)
                        : null;
                result.put(entry.getKey(), info != null ? new OffsetAndTimestamp(info.offset(), info.timestamp()) : null);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 物理逻辑推进分区低水位线（对应 Kafka AdminClient.deleteRecords）
     *
     * @param recordsToDelete 各分区目标裁剪位置映射
     * @return 各分区裁剪后的新低水位异步结果
     */
    public Map<TopicPartition, KafkaFuture<DeletedRecords>> deleteRecords(
            Map<TopicPartition, RecordsToDelete> recordsToDelete) {
        lock.lock();
        try {
            Map<TopicPartition, KafkaFuture<DeletedRecords>> result = new LinkedHashMap<>();
            if (recordsToDelete == null) {
                return result;
            }
            for (Map.Entry<TopicPartition, RecordsToDelete> entry : recordsToDelete.entrySet()) {
                TopicPartition tp = entry.getKey();
                long requestedBefore = entry.getValue() != null ? entry.getValue().beforeOffset() : -1L;
                PartitionState partition = findPartitionLocked(tp);
                long newLowWatermark = 0L;
                if (partition != null) {
                    long leo = partition.records.size();
                    long target = (requestedBefore < 0 || requestedBefore > leo) ? leo : requestedBefore;
                    partition.lowWatermark = Math.max(partition.lowWatermark, target);
                    newLowWatermark = partition.lowWatermark;
                }
                KafkaFutureImpl<DeletedRecords> future = new KafkaFutureImpl<>();
                future.complete(new DeletedRecords(newLowWatermark));
                result.put(tp, future);
            }
            dataAvailable.signalAll();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 提交消费组分区位移
     *
     * @param groupId 消费组 ID
     * @param offsets 待提交位移映射
     */
    public void commitOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (groupId == null || groupId.isBlank() || offsets == null || offsets.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            Map<TopicPartition, OffsetAndMetadata> groupMap =
                    committedOffsets.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    groupMap.put(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查询消费组已提交的全部或指定分区位移
     *
     * @param groupId 消费组 ID
     * @return 已提交分区位移不可变映射
     */
    public Map<TopicPartition, OffsetAndMetadata> getCommittedOffsets(String groupId) {
        lock.lock();
        try {
            Map<TopicPartition, OffsetAndMetadata> groupMap = committedOffsets.get(groupId);
            if (groupMap == null || groupMap.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(groupMap));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建并登记一个离线消费会话
     */
    public ConsumerSession openConsumerSession(
            String consumerId,
            String groupId,
            String keyDeserializerClass,
            String valueDeserializerClass,
            String autoOffsetReset,
            boolean autoCommit) {
        ConsumerSession session = new ConsumerSession(
                consumerId,
                groupId,
                keyDeserializerClass,
                valueDeserializerClass,
                autoOffsetReset,
                autoCommit);
        activeSessions.put(consumerId, session);
        return session;
    }

    /**
     * 关闭消费会话并触发同组剩余成员重平衡
     */
    public void closeConsumerSession(ConsumerSession session) {
        if (session == null) {
            return;
        }
        lock.lock();
        try {
            session.closed = true;
            activeSessions.remove(session.consumerId);
            markGroupForRebalanceLocked(session.groupId);
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 订阅主题列表（声明组重平衡）
     */
    public void subscribe(ConsumerSession session, Collection<String> topicNames, ConsumerRebalanceListener listener) {
        lock.lock();
        try {
            session.subscribedTopics.clear();
            if (topicNames != null) {
                session.subscribedTopics.addAll(topicNames);
            }
            session.rebalanceListener = listener;
            session.manualAssign = false;
            session.rebalancePending = true;
            markGroupForRebalanceLocked(session.groupId);
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取消订阅并清空手动/动态分配
     */
    public void unsubscribe(ConsumerSession session) {
        lock.lock();
        try {
            session.subscribedTopics.clear();
            session.assignedPartitions.clear();
            session.pausedPartitions.clear();
            session.positions.clear();
            session.manualAssign = false;
            session.rebalancePending = false;
            markGroupForRebalanceLocked(session.groupId);
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 手动分配分区集合
     */
    public void assign(ConsumerSession session, Collection<TopicPartition> partitions) {
        lock.lock();
        try {
            session.subscribedTopics.clear();
            session.manualAssign = true;
            session.rebalancePending = false;
            session.assignedPartitions.clear();
            if (partitions != null) {
                for (TopicPartition tp : partitions) {
                    if (tp != null) {
                        session.assignedPartitions.add(tp);
                        session.positions.putIfAbsent(tp, resolveInitialOffsetLocked(session, tp));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置会话游标至指定位置
     */
    public void seek(ConsumerSession session, TopicPartition tp, long offset) {
        lock.lock();
        try {
            session.positions.put(tp, Math.max(0L, offset));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置会话指定分区的游标至起始低水位
     */
    public void seekToBeginning(ConsumerSession session, Collection<TopicPartition> partitions) {
        lock.lock();
        try {
            Collection<TopicPartition> targets = (partitions == null || partitions.isEmpty())
                    ? session.assignedPartitions
                    : partitions;
            for (TopicPartition tp : targets) {
                PartitionState p = findPartitionLocked(tp);
                long low = p != null ? p.lowWatermark : 0L;
                session.positions.put(tp, low);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置会话指定分区的游标至日志末端（LEO）
     */
    public void seekToEnd(ConsumerSession session, Collection<TopicPartition> partitions) {
        lock.lock();
        try {
            Collection<TopicPartition> targets = (partitions == null || partitions.isEmpty())
                    ? session.assignedPartitions
                    : partitions;
            for (TopicPartition tp : targets) {
                PartitionState p = findPartitionLocked(tp);
                long leo = p != null ? p.records.size() : 0L;
                session.positions.put(tp, leo);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取会话在目标分区上的下一个消费偏移量
     */
    public long position(ConsumerSession session, TopicPartition tp) {
        lock.lock();
        try {
            return session.positions.computeIfAbsent(tp, k -> resolveInitialOffsetLocked(session, k));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 暂停拉取指定分区
     */
    public void pause(ConsumerSession session, Collection<TopicPartition> partitions) {
        lock.lock();
        try {
            if (partitions != null) {
                session.pausedPartitions.addAll(partitions);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 恢复拉取指定分区
     */
    public void resume(ConsumerSession session, Collection<TopicPartition> partitions) {
        lock.lock();
        try {
            if (partitions != null) {
                session.pausedPartitions.removeAll(partitions);
                dataAvailable.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 唤醒阻塞在 poll 中的消费会话
     */
    public void wakeup(ConsumerSession session) {
        if (session == null) {
            return;
        }
        lock.lock();
        try {
            session.wokenUp = true;
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行单次受控拉取，含 Rebalance 分发与超时阻塞等待（基于 ReentrantLock/Condition，规避虚拟线程 Pinning）
     */
    public ConsumerRecords<Object, Object> poll(ConsumerSession session, Duration timeout) {
        performRebalanceIfNeeded(session);

        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout != null ? timeout.toNanos() : 0L);
        lock.lock();
        try {
            while (true) {
                if (session.wokenUp) {
                    session.wokenUp = false;
                    throw new WakeupException();
                }
                if (session.closed) {
                    return ConsumerRecords.empty();
                }

                Map<TopicPartition, List<ConsumerRecord<Object, Object>>> batch = drainAvailableRecordsLocked(session);
                if (!batch.isEmpty()) {
                    if (session.autoCommit) {
                        commitCurrentPositionsLocked(session, batch.keySet());
                    }
                    return new ConsumerRecords<>(batch);
                }

                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return ConsumerRecords.empty();
                }
                try {
                    dataAvailable.awaitNanos(remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new WakeupException();
                }

                if (session.rebalancePending) {
                    lock.unlock();
                    try {
                        performRebalanceIfNeeded(session);
                    } finally {
                        lock.lock();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 提交当前会话全部已分配分区的当前拉取位点
     */
    public void commitCurrentPositions(ConsumerSession session) {
        lock.lock();
        try {
            commitCurrentPositionsLocked(session, session.assignedPartitions);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取离线模拟集群唯一标识
     *
     * @return 集群 ID
     */
    public String getClusterId() {
        return CLUSTER_ID;
    }

    /**
     * 获取离线模拟集群节点描述
     *
     * @return 单节点描述
     */
    public Node getClusterNode() {
        return LOCAL_NODE;
    }

    /**
     * 线程安全获取当前消费会话已分配分区快照
     *
     * @param session 消费会话
     * @return 只读分区集合快照
     */
    public Set<TopicPartition> snapshotAssignment(ConsumerSession session) {
        if (session == null) {
            return Collections.emptySet();
        }
        lock.lock();
        try {
            return Collections.unmodifiableSet(new LinkedHashSet<>(session.assignedPartitions));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 线程安全获取当前消费会话订阅主题快照
     *
     * @param session 消费会话
     * @return 只读主题集合快照
     */
    public Set<String> snapshotSubscription(ConsumerSession session) {
        if (session == null) {
            return Collections.emptySet();
        }
        lock.lock();
        try {
            return Collections.unmodifiableSet(new LinkedHashSet<>(session.subscribedTopics));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 线程安全获取当前消费会话已暂停分区快照
     *
     * @param session 消费会话
     * @return 只读已暂停分区集合快照
     */
    public Set<TopicPartition> snapshotPaused(ConsumerSession session) {
        if (session == null) {
            return Collections.emptySet();
        }
        lock.lock();
        try {
            return Collections.unmodifiableSet(new LinkedHashSet<>(session.pausedPartitions));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置全部内存消息、低水位与消费位点（保留主题结构与活跃订阅关系），供测试夹具在 {@code @BeforeEach}/{@code @AfterEach} 隔离现场
     */
    public void reset() {
        lock.lock();
        try {
            for (TopicState state : topics.values()) {
                for (PartitionState partition : state.partitions) {
                    partition.records.clear();
                    partition.lowWatermark = 0L;
                }
            }
            committedOffsets.clear();
            pendingTxRecords.remove();
            for (ConsumerSession session : activeSessions.values()) {
                session.positions.replaceAll((tp, pos) -> 0L);
            }
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void performRebalanceIfNeeded(ConsumerSession session) {
        ConsumerRebalanceListener listener;
        Set<TopicPartition> previousAssignment;
        Set<TopicPartition> nextAssignment;

        lock.lock();
        try {
            if (session.manualAssign || session.subscribedTopics.isEmpty() || !session.rebalancePending) {
                return;
            }
            listener = session.rebalanceListener;
            previousAssignment = new LinkedHashSet<>(session.assignedPartitions);
            nextAssignment = computeGroupAssignmentLocked(session);

            session.assignedPartitions.clear();
            session.assignedPartitions.addAll(nextAssignment);
            session.positions.keySet().retainAll(nextAssignment);
            for (TopicPartition tp : nextAssignment) {
                session.positions.putIfAbsent(tp, resolveInitialOffsetLocked(session, tp));
            }
            session.rebalancePending = false;
        } finally {
            lock.unlock();
        }

        // 在锁外回调用户 RebalanceListener，避免回调重入 seek/committed 时发生锁重入风险
        if (listener != null) {
            if (!previousAssignment.isEmpty()) {
                listener.onPartitionsRevoked(previousAssignment);
            }
            listener.onPartitionsAssigned(nextAssignment);
        }
    }

    private Set<TopicPartition> computeGroupAssignmentLocked(ConsumerSession session) {
        Set<TopicPartition> mine = new LinkedHashSet<>();
        for (String topic : session.subscribedTopics) {
            TopicState state = topics.get(topic);
            if (state == null || state.partitions.isEmpty()) {
                continue;
            }
            // 按消费组与订阅主题交集计算分区归属，避免同 groupId 订阅不同主题的监听容器互相稀释分区
            List<ConsumerSession> topicSubscribers = new ArrayList<>();
            for (ConsumerSession candidate : activeSessions.values()) {
                if (!candidate.closed
                        && !candidate.manualAssign
                        && Objects.equals(candidate.groupId, session.groupId)
                        && candidate.subscribedTopics.contains(topic)) {
                    topicSubscribers.add(candidate);
                }
            }
            topicSubscribers.sort(Comparator.comparing(s -> s.consumerId));
            int myIndex = topicSubscribers.indexOf(session);
            if (myIndex < 0) {
                continue;
            }
            int subscriberCount = topicSubscribers.size();
            for (int p = 0; p < state.partitions.size(); p++) {
                if (p % subscriberCount == myIndex) {
                    mine.add(new TopicPartition(topic, p));
                }
            }
        }
        return mine;
    }

    private Map<TopicPartition, List<ConsumerRecord<Object, Object>>> drainAvailableRecordsLocked(ConsumerSession session) {
        Map<TopicPartition, List<ConsumerRecord<Object, Object>>> result = new LinkedHashMap<>();
        int count = 0;
        for (TopicPartition tp : session.assignedPartitions) {
            if (session.pausedPartitions.contains(tp)) {
                continue;
            }
            PartitionState partition = findPartitionLocked(tp);
            if (partition == null) {
                continue;
            }
            long currentPos = session.positions.computeIfAbsent(tp, k -> resolveInitialOffsetLocked(session, k));
            long startOffset = Math.max(currentPos, partition.lowWatermark);
            long leo = partition.records.size();
            if (startOffset >= leo) {
                continue;
            }
            List<ConsumerRecord<Object, Object>> partitionRecords = new ArrayList<>();
            for (long off = startOffset; off < leo && count < DEFAULT_MAX_POLL_RECORDS; off++) {
                StoredRecord stored = partition.records.get((int) off);
                Object keyObj = deserializeValue(stored.keyBytes, session.keyDeserializerClass, true);
                Object valObj = deserializeValue(stored.valueBytes, session.valueDeserializerClass, false);
                partitionRecords.add(new ConsumerRecord<>(
                        tp.topic(),
                        tp.partition(),
                        stored.offset,
                        stored.timestamp,
                        TimestampType.CREATE_TIME,
                        stored.keyBytes != null ? stored.keyBytes.length : ConsumerRecord.NULL_SIZE,
                        stored.valueBytes != null ? stored.valueBytes.length : ConsumerRecord.NULL_SIZE,
                        keyObj,
                        valObj,
                        copyHeaders(stored.headers),
                        Optional.of(0)));
                session.positions.put(tp, stored.offset + 1);
                count++;
            }
            if (!partitionRecords.isEmpty()) {
                result.put(tp, partitionRecords);
            }
            if (count >= DEFAULT_MAX_POLL_RECORDS) {
                break;
            }
        }
        return result;
    }

    private RecordMetadata appendRecordLocked(
            String topic,
            Integer partition,
            long timestamp,
            byte[] keyBytes,
            byte[] valueBytes,
            Headers headers) {
        TopicState state = topics.computeIfAbsent(topic, k -> new TopicState(k, 1));
        int numPartitions = state.partitions.size();
        int targetPartition;
        if (partition != null && partition >= 0 && partition < numPartitions) {
            targetPartition = partition;
        } else if (keyBytes != null && keyBytes.length > 0) {
            targetPartition = Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        } else {
            targetPartition = Math.floorMod(roundRobinCounter.getAndIncrement(), numPartitions);
        }
        PartitionState partitionState = state.partitions.get(targetPartition);
        long offset = partitionState.records.size();
        partitionState.records.add(new StoredRecord(offset, timestamp, keyBytes, valueBytes, copyHeaders(headers)));
        return new RecordMetadata(
                new TopicPartition(topic, targetPartition),
                offset,
                0,
                timestamp,
                keyBytes != null ? keyBytes.length : -1,
                valueBytes != null ? valueBytes.length : -1);
    }

    private void commitCurrentPositionsLocked(ConsumerSession session, Collection<TopicPartition> partitions) {
        if (session.groupId == null || session.groupId.isBlank() || partitions == null) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> groupMap =
                committedOffsets.computeIfAbsent(session.groupId, k -> new ConcurrentHashMap<>());
        for (TopicPartition tp : partitions) {
            Long pos = session.positions.get(tp);
            if (pos != null) {
                groupMap.put(tp, new OffsetAndMetadata(pos));
            }
        }
    }

    private long resolveInitialOffsetLocked(ConsumerSession session, TopicPartition tp) {
        PartitionState partition = findPartitionLocked(tp);
        long low = partition != null ? partition.lowWatermark : 0L;
        long leo = partition != null ? partition.records.size() : 0L;
        if (session.groupId != null && !session.groupId.isBlank()) {
            Map<TopicPartition, OffsetAndMetadata> groupMap = committedOffsets.get(session.groupId);
            if (groupMap != null && groupMap.containsKey(tp)) {
                return Math.max(low, groupMap.get(tp).offset());
            }
        }
        if ("latest".equalsIgnoreCase(session.autoOffsetReset)) {
            return leo;
        }
        return low;
    }

    private PartitionState findPartitionLocked(TopicPartition tp) {
        if (tp == null) {
            return null;
        }
        TopicState state = topics.get(tp.topic());
        if (state == null || tp.partition() < 0 || tp.partition() >= state.partitions.size()) {
            return null;
        }
        return state.partitions.get(tp.partition());
    }

    private ListOffsetsResult.ListOffsetsResultInfo findOffsetForTimestampLocked(PartitionState partition, long targetTs) {
        for (int i = (int) partition.lowWatermark; i < partition.records.size(); i++) {
            StoredRecord rec = partition.records.get(i);
            if (rec.timestamp >= targetTs) {
                return new ListOffsetsResult.ListOffsetsResultInfo(rec.offset, rec.timestamp, Optional.empty());
            }
        }
        return null;
    }

    private void markSubscribedGroupsForRebalanceLocked() {
        for (ConsumerSession session : activeSessions.values()) {
            if (!session.manualAssign && !session.subscribedTopics.isEmpty()) {
                session.rebalancePending = true;
            }
        }
    }

    private void markGroupForRebalanceLocked(String groupId) {
        if (groupId == null) {
            return;
        }
        for (ConsumerSession session : activeSessions.values()) {
            if (!session.manualAssign && !session.subscribedTopics.isEmpty() && Objects.equals(groupId, session.groupId)) {
                session.rebalancePending = true;
            }
        }
    }

    private static List<PartitionInfo> buildPartitionInfos(TopicState state) {
        List<PartitionInfo> infos = new ArrayList<>(state.partitions.size());
        Node[] nodes = new Node[] { LOCAL_NODE };
        for (int p = 0; p < state.partitions.size(); p++) {
            infos.add(new PartitionInfo(state.name, p, LOCAL_NODE, nodes, nodes));
        }
        return Collections.unmodifiableList(infos);
    }

    private static Object deserializeValue(byte[] bytes, String deserializerClass, boolean isKey) {
        if (bytes == null) {
            return null;
        }
        if (ByteArrayDeserializer.class.getName().equals(deserializerClass)) {
            return Arrays.copyOf(bytes, bytes.length);
        }
        if (isKey || deserializerClass == null || deserializerClass.contains("StringDeserializer")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    private static RecordHeaders copyHeaders(Headers headers) {
        RecordHeaders copy = new RecordHeaders();
        if (headers != null) {
            for (Header header : headers) {
                byte[] val = header.value();
                copy.add(header.key(), val != null ? Arrays.copyOf(val, val.length) : null);
            }
        }
        return copy;
    }

    private static long extractTimestamp(OffsetSpec.TimestampSpec spec) {
        try {
            return (long) TIMESTAMP_SPEC_METHOD.invoke(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 TimestampSpec 时间戳失败", ex);
        }
    }

    private static Method resolveTimestampSpecMethod() {
        try {
            Method method = OffsetSpec.TimestampSpec.class.getDeclaredMethod("timestamp");
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("无法解析 OffsetSpec.TimestampSpec#timestamp", ex);
        }
    }

    /**
     * 内部消费会话状态快照
     */
    public static final class ConsumerSession {
        final String consumerId;
        final String groupId;
        final String keyDeserializerClass;
        final String valueDeserializerClass;
        final String autoOffsetReset;
        final boolean autoCommit;
        final Set<String> subscribedTopics = new LinkedHashSet<>();
        final Set<TopicPartition> assignedPartitions = new LinkedHashSet<>();
        final Set<TopicPartition> pausedPartitions = new LinkedHashSet<>();
        final Map<TopicPartition, Long> positions = new LinkedHashMap<>();
        ConsumerRebalanceListener rebalanceListener;
        boolean manualAssign;
        boolean rebalancePending;
        boolean closed;
        boolean wokenUp;

        ConsumerSession(
                String consumerId,
                String groupId,
                String keyDeserializerClass,
                String valueDeserializerClass,
                String autoOffsetReset,
                boolean autoCommit) {
            this.consumerId = consumerId;
            this.groupId = groupId;
            this.keyDeserializerClass = keyDeserializerClass;
            this.valueDeserializerClass = valueDeserializerClass;
            this.autoOffsetReset = autoOffsetReset;
            this.autoCommit = autoCommit;
        }
    }

    private static final class TopicState {
        final String name;
        final List<PartitionState> partitions;

        TopicState(String name, int numPartitions) {
            this.name = name;
            this.partitions = new ArrayList<>(numPartitions);
            for (int i = 0; i < numPartitions; i++) {
                this.partitions.add(new PartitionState());
            }
        }

        void expandPartitions(int targetPartitions) {
            while (partitions.size() < targetPartitions) {
                partitions.add(new PartitionState());
            }
        }
    }

    private static final class PartitionState {
        final List<StoredRecord> records = new ArrayList<>();
        long lowWatermark = 0L;
    }

    private record StoredRecord(
            long offset,
            long timestamp,
            byte[] keyBytes,
            byte[] valueBytes,
            Headers headers) {
    }

    private record PendingPublish(
            String topic,
            Integer partition,
            long timestamp,
            byte[] keyBytes,
            byte[] valueBytes,
            Headers headers) {
    }
}
