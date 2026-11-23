package io.github.atengk.boot.sample.kafka.offline;

import io.github.atengk.boot.core.util.JsonUtil;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.KafkaException;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * 基于 JDK 动态代理构建离线 {@link Consumer} 与 {@link Producer} 驱动实现，零 Mockito 依赖且屏蔽一切原生 Kafka 网络连接
 *
 * @author Ateng
 * @since 2026-11-15
 */
public final class OfflineKafkaProxyFactory {

    private OfflineKafkaProxyFactory() {
    }

    /**
     * 将消息键序列化为底层字节数组（对齐默认 {@code StringSerializer} 行为）
     *
     * @param key 待序列化消息键（可为 null、byte[]、String 或 POJO）
     * @return 字节数组（入参为 null 时返回 null）
     */
    public static byte[] serializeKeyToBytes(Object key) {
        if (key == null) {
            return null;
        }
        if (key instanceof byte[] bytes) {
            return bytes;
        }
        if (key instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return JsonUtil.getObjectMapper().writeValueAsBytes(key);
        } catch (Exception ex) {
            throw new IllegalStateException("离线 Kafka Key JSON 序列化失败: " + key.getClass().getName(), ex);
        }
    }

    /**
     * 将消息值序列化为底层字节数组（对齐 {@code application.yml} 中的 {@code JsonSerializer} 行为：除原始 byte[] 外均执行 JSON 编码）
     *
     * @param payload 待序列化载荷（可为 null、byte[]、String 或 POJO）
     * @return 字节数组（入参为 null 时返回 null）
     */
    public static byte[] serializeValueToBytes(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        try {
            return JsonUtil.getObjectMapper().writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("离线 Kafka 载荷 JSON 序列化失败: " + payload.getClass().getName(), ex);
        }
    }

    /**
     * 创建绑定至内存模拟 Broker 的离线 {@link Consumer} 代理实例
     *
     * @param broker 离线内存模拟 Broker
     * @param groupId 消费组 ID
     * @param configs 合并后的消费者配置映射
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 离线消费者代理
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Consumer<K, V> createConsumer(
            OfflineKafkaBroker broker,
            String groupId,
            Map<String, Object> configs) {
        Objects.requireNonNull(broker, "broker 不能为空");
        Map<String, Object> safeConfigs = configs != null ? configs : Collections.emptyMap();
        String effectiveGroupId = resolveGroupId(groupId, safeConfigs);
        String keyDeserializer = resolveClassName(safeConfigs.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        String valueDeserializer = resolveClassName(safeConfigs.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        Object resetCfg = safeConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        String autoOffsetReset = resetCfg != null ? String.valueOf(resetCfg) : "earliest";
        Object autoCommitCfg = safeConfigs.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        boolean autoCommit = autoCommitCfg != null && Boolean.parseBoolean(String.valueOf(autoCommitCfg));

        OfflineKafkaBroker.ConsumerSession session = broker.openConsumerSession(
                "offline-consumer-" + UUID.randomUUID(),
                effectiveGroupId,
                keyDeserializer,
                valueDeserializer,
                autoOffsetReset,
                autoCommit);

        return (Consumer<K, V>) Proxy.newProxyInstance(
                Consumer.class.getClassLoader(),
                new Class<?>[] { Consumer.class },
                (proxy, method, args) -> handleConsumerInvocation(broker, session, proxy, method.getName(), args));
    }

    /**
     * 创建绑定至内存模拟 Broker 的离线 {@link Producer} 代理实例
     *
     * @param broker 离线内存模拟 Broker
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 离线生产者代理
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Producer<K, V> createProducer(OfflineKafkaBroker broker) {
        Objects.requireNonNull(broker, "broker 不能为空");
        return (Producer<K, V>) Proxy.newProxyInstance(
                Producer.class.getClassLoader(),
                new Class<?>[] { Producer.class },
                (proxy, method, args) -> handleProducerInvocation(broker, proxy, method.getName(), args));
    }

    @SuppressWarnings("unchecked")
    private static Object handleConsumerInvocation(
            OfflineKafkaBroker broker,
            OfflineKafkaBroker.ConsumerSession session,
            Object proxy,
            String methodName,
            Object[] args) {
        return switch (methodName) {
            case "toString" -> "OfflineKafkaConsumer[" + session.consumerId + ", group=" + session.groupId + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "assignment" -> broker.snapshotAssignment(session);
            case "subscription" -> broker.snapshotSubscription(session);
            case "subscribe" -> {
                handleSubscribe(broker, session, args);
                yield null;
            }
            case "unsubscribe" -> {
                broker.unsubscribe(session);
                yield null;
            }
            case "assign" -> {
                Collection<TopicPartition> partitions = args != null && args.length > 0
                        ? (Collection<TopicPartition>) args[0]
                        : Collections.emptyList();
                broker.assign(session, partitions);
                yield null;
            }
            case "poll" -> {
                Duration timeout = Duration.ZERO;
                if (args != null && args.length > 0) {
                    if (args[0] instanceof Duration d) {
                        timeout = d;
                    } else if (args[0] instanceof Long millis) {
                        timeout = Duration.ofMillis(Math.max(0L, millis));
                    }
                }
                yield broker.poll(session, timeout);
            }
            case "commitSync" -> {
                if (args != null && args.length > 0 && args[0] instanceof Map<?, ?> offsets) {
                    broker.commitOffsets(session.groupId, (Map<TopicPartition, OffsetAndMetadata>) offsets);
                } else {
                    broker.commitCurrentPositions(session);
                }
                yield null;
            }
            case "commitAsync" -> {
                handleCommitAsync(broker, session, args);
                yield null;
            }
            case "seek" -> {
                TopicPartition tp = requireArg(args, 0, TopicPartition.class, "seek");
                Object second = requireArg(args, 1, Object.class, "seek");
                if (second instanceof Long offset) {
                    broker.seek(session, tp, offset);
                } else if (second instanceof OffsetAndMetadata meta) {
                    broker.seek(session, tp, meta.offset());
                } else {
                    throw new IllegalArgumentException("seek 参数不支持类型: " + second.getClass().getName());
                }
                yield null;
            }
            case "seekToBeginning" -> {
                broker.seekToBeginning(session, args != null && args.length > 0
                        ? (Collection<TopicPartition>) args[0]
                        : Collections.emptyList());
                yield null;
            }
            case "seekToEnd" -> {
                broker.seekToEnd(session, args != null && args.length > 0
                        ? (Collection<TopicPartition>) args[0]
                        : Collections.emptyList());
                yield null;
            }
            case "position" -> broker.position(session, requireArg(args, 0, TopicPartition.class, "position"));
            case "committed" -> handleCommitted(broker, session, args);
            case "partitionsFor" -> broker.partitionsFor(requireArg(args, 0, String.class, "partitionsFor"));
            case "listTopics" -> broker.listAllTopics();
            case "paused" -> broker.snapshotPaused(session);
            case "pause" -> {
                broker.pause(session, requireArg(args, 0, Collection.class, "pause"));
                yield null;
            }
            case "resume" -> {
                broker.resume(session, requireArg(args, 0, Collection.class, "resume"));
                yield null;
            }
            case "offsetsForTimes" -> broker.offsetsForTimes(requireArg(args, 0, Map.class, "offsetsForTimes"));
            case "beginningOffsets" -> queryBoundaryOffsets(broker, requireArg(args, 0, Collection.class, "beginningOffsets"), OffsetSpec.earliest());
            case "endOffsets" -> queryBoundaryOffsets(broker, requireArg(args, 0, Collection.class, "endOffsets"), OffsetSpec.latest());
            case "currentLag" -> {
                TopicPartition tp = requireArg(args, 0, TopicPartition.class, "currentLag");
                Map<TopicPartition, Long> end = queryBoundaryOffsets(broker, Collections.singleton(tp), OffsetSpec.latest());
                long leo = end.getOrDefault(tp, 0L);
                long pos = broker.position(session, tp);
                yield OptionalLong.of(Math.max(0L, leo - pos));
            }
            case "groupMetadata" -> new ConsumerGroupMetadata(session.groupId);
            case "metrics" -> Collections.emptyMap();
            case "enforceRebalance" -> null;
            case "wakeup" -> {
                broker.wakeup(session);
                yield null;
            }
            case "close" -> {
                broker.closeConsumerSession(session);
                yield null;
            }
            default -> throw new UnsupportedOperationException("离线 Consumer 暂未模拟方法: " + methodName);
        };
    }

    @SuppressWarnings("unchecked")
    private static void handleSubscribe(
            OfflineKafkaBroker broker,
            OfflineKafkaBroker.ConsumerSession session,
            Object[] args) {
        if (args == null || args.length == 0) {
            broker.unsubscribe(session);
            return;
        }
        ConsumerRebalanceListener listener = (args.length > 1 && args[1] instanceof ConsumerRebalanceListener l) ? l : null;
        if (args[0] instanceof Collection<?> topics) {
            broker.subscribe(session, (Collection<String>) topics, listener);
            return;
        }
        if (args[0] instanceof Pattern pattern) {
            Set<String> matched = new LinkedHashSet<>();
            for (String topic : broker.listAllTopics().keySet()) {
                if (pattern.matcher(topic).matches()) {
                    matched.add(topic);
                }
            }
            broker.subscribe(session, matched, listener);
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleCommitAsync(
            OfflineKafkaBroker broker,
            OfflineKafkaBroker.ConsumerSession session,
            Object[] args) {
        if (args == null || args.length == 0) {
            broker.commitCurrentPositions(session);
            return;
        }
        if (args[0] instanceof OffsetCommitCallback callback) {
            broker.commitCurrentPositions(session);
            callback.onComplete(broker.getCommittedOffsets(session.groupId), null);
            return;
        }
        if (args[0] instanceof Map<?, ?> offsets) {
            Map<TopicPartition, OffsetAndMetadata> cast = (Map<TopicPartition, OffsetAndMetadata>) offsets;
            broker.commitOffsets(session.groupId, cast);
            if (args.length > 1 && args[1] instanceof OffsetCommitCallback callback) {
                callback.onComplete(cast, null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object handleCommitted(
            OfflineKafkaBroker broker,
            OfflineKafkaBroker.ConsumerSession session,
            Object[] args) {
        Map<TopicPartition, OffsetAndMetadata> all = broker.getCommittedOffsets(session.groupId);
        if (args != null && args.length > 0 && args[0] instanceof TopicPartition tp) {
            return all.get(tp);
        }
        if (args != null && args.length > 0 && args[0] instanceof Set<?> partitions) {
            Map<TopicPartition, OffsetAndMetadata> filtered = new LinkedHashMap<>();
            for (TopicPartition tp : (Set<TopicPartition>) partitions) {
                if (all.containsKey(tp)) {
                    filtered.put(tp, all.get(tp));
                }
            }
            return filtered;
        }
        return all;
    }

    private static Map<TopicPartition, Long> queryBoundaryOffsets(
            OfflineKafkaBroker broker,
            Collection<TopicPartition> partitions,
            OffsetSpec spec) {
        if (partitions == null || partitions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TopicPartition, OffsetSpec> req = new LinkedHashMap<>();
        for (TopicPartition tp : partitions) {
            req.put(tp, spec);
        }
        Map<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> futures = broker.listOffsets(req);
        Map<TopicPartition, Long> result = new LinkedHashMap<>(futures.size());
        for (Map.Entry<TopicPartition, KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo>> entry : futures.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get().offset());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new KafkaException("查询离线边界位移被中断: " + entry.getKey(), ex);
            } catch (Exception ex) {
                throw new KafkaException("查询离线边界位移失败: " + entry.getKey(), ex);
            }
        }
        return result;
    }

    private static <T> T requireArg(Object[] args, int index, Class<T> type, String methodName) {
        if (args == null || args.length <= index || args[index] == null) {
            throw new IllegalArgumentException(methodName + " 缺少必需参数 [" + index + "]");
        }
        return type.cast(args[index]);
    }

    @SuppressWarnings("unchecked")
    private static Object handleProducerInvocation(
            OfflineKafkaBroker broker,
            Object proxy,
            String methodName,
            Object[] args) {
        return switch (methodName) {
            case "toString" -> "OfflineKafkaProducer";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "send" -> handleProducerSend(broker, args);
            case "partitionsFor" -> broker.partitionsFor((String) args[0]);
            case "flush", "initTransactions", "close" -> null;
            case "beginTransaction" -> {
                broker.beginTransaction();
                yield null;
            }
            case "commitTransaction" -> {
                broker.commitTransaction();
                yield null;
            }
            case "abortTransaction" -> {
                broker.rollbackTransaction();
                yield null;
            }
            case "sendOffsetsToTransaction" -> {
                if (args != null && args.length >= 2 && args[1] instanceof ConsumerGroupMetadata meta) {
                    broker.commitOffsets(meta.groupId(), (Map<TopicPartition, OffsetAndMetadata>) args[0]);
                }
                yield null;
            }
            case "metrics" -> Collections.emptyMap();
            default -> throw new UnsupportedOperationException("离线 Producer 暂未模拟方法: " + methodName);
        };
    }

    private static Future<RecordMetadata> handleProducerSend(OfflineKafkaBroker broker, Object[] args) {
        ProducerRecord<?, ?> record = requireArg(args, 0, ProducerRecord.class, "send");
        Callback callback = (args.length > 1 && args[1] instanceof Callback cb) ? cb : null;
        RecordMetadata metadata = broker.publish(
                record.topic(),
                record.partition(),
                record.timestamp(),
                serializeKeyToBytes(record.key()),
                serializeValueToBytes(record.value()),
                record.headers());
        if (callback != null) {
            callback.onCompletion(metadata, null);
        }
        return CompletableFuture.completedFuture(metadata);
    }

    private static String resolveGroupId(String argGroupId, Map<String, Object> configs) {
        if (argGroupId != null && !argGroupId.isBlank()) {
            return argGroupId;
        }
        Object cfgGroupId = configs.get(ConsumerConfig.GROUP_ID_CONFIG);
        return cfgGroupId != null ? String.valueOf(cfgGroupId) : "offline-group";
    }

    private static String resolveClassName(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?> clazz) {
            return clazz.getName();
        }
        return String.valueOf(value);
    }
}
