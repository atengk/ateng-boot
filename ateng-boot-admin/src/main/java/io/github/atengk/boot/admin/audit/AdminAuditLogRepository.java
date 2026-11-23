package io.github.atengk.boot.admin.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有界环形内存审计仓库（基于 {@link ReentrantLock} 遵守虚拟线程零 Pinning 规范）
 * <p>
 * 严格限制内存保留上限（默认 500 条），超出容量后按 FIFO 语义自动淘汰最旧记录，杜绝堆内存无限膨胀。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class AdminAuditLogRepository {

    public static final int DEFAULT_MAX_RECORDS = 500;

    private final int maxRecords;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<AdminAuditRecord> ringBuffer;

    public AdminAuditLogRepository() {
        this(DEFAULT_MAX_RECORDS);
    }

    public AdminAuditLogRepository(int maxRecords) {
        this.maxRecords = maxRecords > 0 ? maxRecords : DEFAULT_MAX_RECORDS;
        this.ringBuffer = new ArrayDeque<>(Math.min(this.maxRecords, 1024));
    }

    /**
     * 写入一条审计记录；超出容量时淘汰最早记录
     *
     * @param record 审计记录
     */
    public void record(AdminAuditRecord record) {
        if (record == null) {
            return;
        }
        lock.lock();
        try {
            while (ringBuffer.size() >= maxRecords) {
                ringBuffer.pollFirst();
            }
            ringBuffer.addLast(record);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按发生时间倒序（最新在前）检索审计记录
     *
     * @param action 动作类型过滤条件（null 表示不过滤）
     * @param actor  操作主体过滤条件（空白表示不过滤，精确忽略大小写匹配）
     * @param limit  返回最大条数（自动钳制在 [1, maxRecords] 区间）
     * @return 不可变审计记录列表（永不返回 null）
     */
    public List<AdminAuditRecord> query(AdminAuditAction action, String actor, int limit) {
        int effectiveLimit = limit <= 0 ? maxRecords : Math.min(limit, maxRecords);
        String normalizedActor = (actor != null && !actor.isBlank()) ? actor.trim() : null;

        lock.lock();
        try {
            if (ringBuffer.isEmpty()) {
                return Collections.emptyList();
            }
            List<AdminAuditRecord> results = new ArrayList<>(Math.min(effectiveLimit, ringBuffer.size()));
            Iterator<AdminAuditRecord> descending = ringBuffer.descendingIterator();
            while (descending.hasNext() && results.size() < effectiveLimit) {
                AdminAuditRecord candidate = descending.next();
                if (action != null && candidate.getAction() != action) {
                    continue;
                }
                if (normalizedActor != null && !normalizedActor.equalsIgnoreCase(candidate.getActor())) {
                    continue;
                }
                results.add(candidate);
            }
            return List.copyOf(results);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前缓存记录数
     *
     * @return 当前条数
     */
    public int size() {
        lock.lock();
        try {
            return ringBuffer.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空内存审计仓库（主要用于沙箱测试重置）
     */
    public void clear() {
        lock.lock();
        try {
            ringBuffer.clear();
        } finally {
            lock.unlock();
        }
    }
}
