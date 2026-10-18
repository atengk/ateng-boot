package io.github.atengk.boot.sample.storage.listener;

import io.github.atengk.boot.storage.event.StorageDeletedEvent;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 存储生命周期领域事件监听器样例
 * 演示通过 Spring 原生 @EventListener 监听 StorageUploadedEvent 与 StorageDeletedEvent
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Slf4j
@Component
public class SampleStorageEventListener {

    private final AtomicInteger uploadCount = new AtomicInteger();
    private final AtomicInteger deleteCount = new AtomicInteger();

    @EventListener
    public void onUploaded(StorageUploadedEvent event) {
        log.info("[SampleStorageEventListener] 接收到文件上传事件: source={}, path={}, size={}, contentType={}",
                event.getSourceKey(), event.getPath(), event.getSize(), event.getContentType());
        uploadCount.incrementAndGet();
    }

    @EventListener
    public void onDeleted(StorageDeletedEvent event) {
        log.info("[SampleStorageEventListener] 接收到文件删除事件: source={}, path={}",
                event.getSourceKey(), event.getPath());
        deleteCount.incrementAndGet();
    }

    /**
     * 获取上传事件触发总数
     *
     * @return 上传事件次数
     */
    public int getUploadCount() {
        return uploadCount.get();
    }

    /**
     * 获取删除事件触发总数
     *
     * @return 删除事件次数
     */
    public int getDeleteCount() {
        return deleteCount.get();
    }

    /**
     * 重置计数器（供单测生命周期使用）
     */
    public void reset() {
        uploadCount.set(0);
        deleteCount.set(0);
    }
}
