package io.github.atengk.boot.job.local.storage;

import io.github.atengk.boot.job.local.model.JobExecutionRecord;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于内存的有界环形队列任务执行日志存储提供者
 * <p>
 * 默认每任务保留固定条数（默认 100 条）执行记录，超出容量时自动执行 FIFO 淘汰，杜绝高频调度堆内存溢出。
 * 全链路采用 {@link ReentrantLock} 替代内置监视器锁，消除 JDK 21+ 虚拟线程 Carrier Pinning。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class InMemoryJobLogStorageProvider implements JobLogStorageProvider {

    private final int capacity;
    private final ConcurrentMap<String, LogBucket> logMap = new ConcurrentHashMap<>();

    public InMemoryJobLogStorageProvider() {
        this(100);
    }

    public InMemoryJobLogStorageProvider(int capacity) {
        this.capacity = capacity > 0 ? capacity : 100;
    }

    @Override
    public void saveLog(JobExecutionRecord record) {
        if (record == null || record.getJobName() == null) {
            return;
        }
        LogBucket bucket = logMap.computeIfAbsent(record.getJobName(), k -> new LogBucket());
        bucket.lock.lock();
        try {
            bucket.queue.addFirst(record);
            while (bucket.queue.size() > capacity) {
                bucket.queue.removeLast();
            }
        } finally {
            if (bucket.lock.isHeldByCurrentThread()) {
                bucket.lock.unlock();
            }
        }
    }

    @Override
    public List<JobExecutionRecord> listLogs(String jobName, int limit) {
        if (jobName == null) {
            return Collections.emptyList();
        }
        LogBucket bucket = logMap.get(jobName);
        if (bucket == null) {
            return Collections.emptyList();
        }
        bucket.lock.lock();
        try {
            int max = limit > 0 ? limit : capacity;
            List<JobExecutionRecord> result = new ArrayList<>(Math.min(bucket.queue.size(), max));
            int count = 0;
            for (JobExecutionRecord r : bucket.queue) {
                if (count++ >= max) {
                    break;
                }
                result.add(r);
            }
            return Collections.unmodifiableList(result);
        } finally {
            if (bucket.lock.isHeldByCurrentThread()) {
                bucket.lock.unlock();
            }
        }
    }

    @Override
    public int cleanExpiredLogs(Instant expireBefore) {
        if (expireBefore == null) {
            return 0;
        }
        int cleaned = 0;
        for (LogBucket bucket : logMap.values()) {
            bucket.lock.lock();
            try {
                Iterator<JobExecutionRecord> iterator = bucket.queue.iterator();
                while (iterator.hasNext()) {
                    JobExecutionRecord record = iterator.next();
                    if (record.getFireTime() != null && record.getFireTime().isBefore(expireBefore)) {
                        iterator.remove();
                        cleaned++;
                    }
                }
            } finally {
                if (bucket.lock.isHeldByCurrentThread()) {
                    bucket.lock.unlock();
                }
            }
        }
        return cleaned;
    }

    /**
     * 获取当前配置的有界队列容量上限
     *
     * @return 单任务最大记录条数
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 获取指定任务当前内存队列中的实际日志数量（用于测试与监控断言）
     *
     * @param jobName 任务名称
     * @return 当前记录条数
     */
    public int getLogCount(String jobName) {
        if (jobName == null) {
            return 0;
        }
        LogBucket bucket = logMap.get(jobName);
        if (bucket == null) {
            return 0;
        }
        bucket.lock.lock();
        try {
            return bucket.queue.size();
        } finally {
            if (bucket.lock.isHeldByCurrentThread()) {
                bucket.lock.unlock();
            }
        }
    }

    /**
     * 清理所有内存日志记录
     */
    public void clear() {
        logMap.clear();
    }

    private static final class LogBucket {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<JobExecutionRecord> queue = new ArrayDeque<>();
    }
}
