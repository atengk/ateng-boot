package io.github.atengk.boot.ai.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;

/**
 * 单机内存认知图检查点存储器实现类
 * <p>
 * 基于并发安全读写锁与双端队列管理检查点历史，专用于单机开发调试与 100% 离线自洽单元测试。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class MemoryCheckpointSaver implements GraphCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(MemoryCheckpointSaver.class);

    private final Map<String, LinkedList<Checkpoint>> checkpointsByThread = new ConcurrentHashMap<>();
    private final LinkedList<Checkpoint> defaultCheckpoints = new LinkedList<>();

    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final Lock readLock = rwl.readLock();
    private final Lock writeLock = rwl.writeLock();

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        LinkedList<Checkpoint> list = getCheckpoints(config);
        readLock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(list));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        LinkedList<Checkpoint> list = getCheckpoints(config);
        readLock.lock();
        try {
            if (list.isEmpty()) {
                return Optional.empty();
            }
            if (config.checkPointId().isPresent()) {
                String targetId = config.checkPointId().get();
                return list.stream()
                        .filter(cp -> Objects.equals(cp.getId(), targetId))
                        .findFirst();
            }
            return Optional.ofNullable(list.peekFirst());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        Objects.requireNonNull(config, "RunnableConfig must not be null");
        Objects.requireNonNull(checkpoint, "Checkpoint must not be null");

        LinkedList<Checkpoint> list = getCheckpoints(config);
        writeLock.lock();
        try {
            if (config.checkPointId().isPresent()) {
                String targetId = config.checkPointId().get();
                int index = IntStream.range(0, list.size())
                        .filter(i -> Objects.equals(list.get(i).getId(), targetId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + targetId));
                list.set(index, checkpoint);
                return config;
            }

            list.push(checkpoint);
            return RunnableConfig.builder(config)
                    .checkPointId(checkpoint.getId())
                    .build();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean exists(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        readLock.lock();
        try {
            LinkedList<Checkpoint> list = checkpointsByThread.get(threadId);
            return list != null && !list.isEmpty();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clear(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        writeLock.lock();
        try {
            checkpointsByThread.remove(threadId);
            log.debug("已清空线程会话 [{}] 的内存检查点记录", threadId);
        } finally {
            writeLock.unlock();
        }
    }

    private LinkedList<Checkpoint> getCheckpoints(RunnableConfig config) {
        return config.threadId()
                .map(tid -> checkpointsByThread.computeIfAbsent(tid, k -> new LinkedList<>()))
                .orElse(defaultCheckpoints);
    }
}
