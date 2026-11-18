package io.github.atengk.boot.mqtt.dlt;

import io.github.atengk.boot.mqtt.model.MqttDeadLetterRecord;
import io.github.atengk.boot.mqtt.properties.MqttDltProperties;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认有界内存 MQTT 死信存储实现
 * 支持命名客户端隔离、单 DLT 主题容量上限与全局跨主题容量上限双维度 FIFO 自动淘汰，使用可重入锁保障高并发与虚拟线程友好性
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class InMemoryMqttDeadLetterStore implements MqttDeadLetterStore {

    private static final String DEFAULT_CLIENT_SCOPE = "__default__";

    private final int maxRecordsPerTopic;
    private final int maxTotalRecords;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ScopeKey, LinkedHashMap<String, MqttDeadLetterRecord>> scopedRecords = new HashMap<>();
    private final LinkedHashMap<GlobalKey, MqttDeadLetterRecord> globalIndex = new LinkedHashMap<>();

    /**
     * 使用默认容量上限（单主题 1000、全局 10000）构建内存死信存储
     */
    public InMemoryMqttDeadLetterStore() {
        this(1000, 10000);
    }

    /**
     * 根据死信配置属性构建内存死信存储
     *
     * @param properties 死信策略配置属性
     */
    public InMemoryMqttDeadLetterStore(MqttDltProperties properties) {
        this(
                properties != null ? properties.getMaxRecordsPerTopic() : 1000,
                properties != null ? properties.getMaxTotalRecords() : 10000
        );
    }

    /**
     * 指定单主题上限与全局上限构建内存死信存储
     *
     * @param maxRecordsPerTopic 单个 DLT 主题最大保留条数
     * @param maxTotalRecords    全局跨主题最大保留总条数
     */
    public InMemoryMqttDeadLetterStore(int maxRecordsPerTopic, int maxTotalRecords) {
        if (maxRecordsPerTopic <= 0) {
            throw new IllegalArgumentException("maxRecordsPerTopic must be positive: " + maxRecordsPerTopic);
        }
        if (maxTotalRecords <= 0) {
            throw new IllegalArgumentException("maxTotalRecords must be positive: " + maxTotalRecords);
        }
        this.maxRecordsPerTopic = maxRecordsPerTopic;
        this.maxTotalRecords = maxTotalRecords;
    }

    @Override
    public void save(MqttDeadLetterRecord record) {
        save(null, record);
    }

    @Override
    public void save(String clientName, MqttDeadLetterRecord record) {
        Assert.notNull(record, "record must not be null");
        Assert.hasText(record.dltTopic(), "dltTopic must not be blank");

        String scopeClient = normalizeClient(clientName);
        String dltTopic = record.dltTopic();
        ScopeKey scopeKey = new ScopeKey(scopeClient, dltTopic);
        GlobalKey newKey = new GlobalKey(scopeClient, dltTopic, record.recordId());

        lock.lock();
        try {
            LinkedHashMap<String, MqttDeadLetterRecord> queue =
                    scopedRecords.computeIfAbsent(scopeKey, k -> new LinkedHashMap<>());

            // 若同作用域内相同 recordId 已存在，双索引同步移除旧条目以更新 FIFO 位置并防重复计数
            if (queue.remove(record.recordId()) != null) {
                globalIndex.remove(newKey);
            }

            // 1. 守护单主题上限：FIFO 淘汰最旧条目并同步清理全局索引
            int effectiveTopicCap = Math.min(maxRecordsPerTopic, maxTotalRecords);
            while (queue.size() >= effectiveTopicCap && !queue.isEmpty()) {
                Iterator<Map.Entry<String, MqttDeadLetterRecord>> it = queue.entrySet().iterator();
                Map.Entry<String, MqttDeadLetterRecord> eldest = it.next();
                it.remove();
                globalIndex.remove(new GlobalKey(scopeClient, dltTopic, eldest.getKey()));
            }

            // 2. 守护全局跨主题总上限：FIFO 淘汰全局最旧条目并同步清理分桶队列
            while (globalIndex.size() >= maxTotalRecords && !globalIndex.isEmpty()) {
                Iterator<Map.Entry<GlobalKey, MqttDeadLetterRecord>> git = globalIndex.entrySet().iterator();
                GlobalKey oldestKey = git.next().getKey();
                git.remove();
                ScopeKey oldestScope = new ScopeKey(oldestKey.clientName(), oldestKey.dltTopic());
                LinkedHashMap<String, MqttDeadLetterRecord> targetQueue = scopedRecords.get(oldestScope);
                if (targetQueue != null) {
                    targetQueue.remove(oldestKey.recordId());
                    if (targetQueue != queue && targetQueue.isEmpty()) {
                        scopedRecords.remove(oldestScope);
                    }
                }
            }

            queue.put(record.recordId(), record);
            globalIndex.put(newKey, record);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<MqttDeadLetterRecord> peek(String dltTopic, int limit) {
        return peek(null, dltTopic, limit);
    }

    @Override
    public List<MqttDeadLetterRecord> peek(String clientName, String dltTopic, int limit) {
        Assert.hasText(dltTopic, "dltTopic must not be blank");
        Assert.isTrue(limit > 0, "limit must be greater than 0");

        ScopeKey scopeKey = new ScopeKey(normalizeClient(clientName), dltTopic);
        lock.lock();
        try {
            LinkedHashMap<String, MqttDeadLetterRecord> queue = scopedRecords.get(scopeKey);
            if (queue == null || queue.isEmpty()) {
                return Collections.emptyList();
            }
            List<MqttDeadLetterRecord> result = new ArrayList<>(Math.min(limit, queue.size()));
            for (MqttDeadLetterRecord record : queue.values()) {
                result.add(record);
                if (result.size() >= limit) {
                    break;
                }
            }
            return Collections.unmodifiableList(result);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(String dltTopic, String recordId) {
        return remove(null, dltTopic, recordId);
    }

    @Override
    public boolean remove(String clientName, String dltTopic, String recordId) {
        if (!StringUtils.hasText(dltTopic) || !StringUtils.hasText(recordId)) {
            return false;
        }
        String scopeClient = normalizeClient(clientName);
        ScopeKey scopeKey = new ScopeKey(scopeClient, dltTopic);
        lock.lock();
        try {
            LinkedHashMap<String, MqttDeadLetterRecord> queue = scopedRecords.get(scopeKey);
            if (queue == null) {
                return false;
            }
            MqttDeadLetterRecord removed = queue.remove(recordId);
            if (removed == null) {
                return false;
            }
            globalIndex.remove(new GlobalKey(scopeClient, dltTopic, recordId));
            if (queue.isEmpty()) {
                scopedRecords.remove(scopeKey);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int purge(String dltTopic) {
        return purge(null, dltTopic);
    }

    @Override
    public int purge(String clientName, String dltTopic) {
        Assert.hasText(dltTopic, "dltTopic must not be blank");

        String scopeClient = normalizeClient(clientName);
        ScopeKey scopeKey = new ScopeKey(scopeClient, dltTopic);
        lock.lock();
        try {
            LinkedHashMap<String, MqttDeadLetterRecord> removed = scopedRecords.remove(scopeKey);
            if (removed == null || removed.isEmpty()) {
                return 0;
            }
            for (String recordId : removed.keySet()) {
                globalIndex.remove(new GlobalKey(scopeClient, dltTopic, recordId));
            }
            return removed.size();
        } finally {
            lock.unlock();
        }
    }

    private String normalizeClient(String clientName) {
        return StringUtils.hasText(clientName) ? clientName : DEFAULT_CLIENT_SCOPE;
    }

    private record ScopeKey(String clientName, String dltTopic) {
    }

    private record GlobalKey(String clientName, String dltTopic, String recordId) {
    }
}
