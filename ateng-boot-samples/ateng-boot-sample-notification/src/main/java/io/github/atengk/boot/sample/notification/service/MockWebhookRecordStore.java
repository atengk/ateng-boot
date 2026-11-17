package io.github.atengk.boot.sample.notification.service;

import io.github.atengk.boot.sample.notification.vo.MockWebhookRecordVO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 离线仿真 Webhook 网关录制报文内存存储组件（仅在非 local Profile 下装配）
 * 将状态存储从 Controller 剥离，保证控制器无状态并以有界并发队列保存审计报文
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Component
@Profile("!local")
public class MockWebhookRecordStore {

    private static final int MAX_RECORD_CAPACITY = 200;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<MockWebhookRecordVO> records = new ConcurrentLinkedDeque<>();

    /**
     * 追加一条录制报文（超出容量上限时淘汰最旧记录）
     *
     * @param record 审计报文视图对象
     */
    public void append(MockWebhookRecordVO record) {
        if (record == null) {
            return;
        }
        lock.lock();
        try {
            while (records.size() >= MAX_RECORD_CAPACITY) {
                records.pollFirst();
            }
            records.addLast(record);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 查询当前录制的全部审计报文快照（可选按平台过滤）
     *
     * @param platform 可选平台名称（不区分大小写）
     * @return 审计报文不可变快照列表
     */
    public List<MockWebhookRecordVO> list(String platform) {
        String normalizedPlatform = (platform != null && !platform.isBlank())
                ? platform.trim().toUpperCase(Locale.ROOT)
                : null;
        lock.lock();
        try {
            List<MockWebhookRecordVO> snapshot = new ArrayList<>(records.size());
            for (MockWebhookRecordVO record : records) {
                if (normalizedPlatform == null || normalizedPlatform.equals(record.getPlatform())) {
                    snapshot.add(record);
                }
            }
            return snapshot;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 清空当前所有录制报文并返回清理条数
     *
     * @return 被清空的报文数量
     */
    public int clear() {
        lock.lock();
        try {
            int count = records.size();
            records.clear();
            return count;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
