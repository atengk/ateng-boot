package io.github.atengk.boot.sample.storage.listener;

import io.github.atengk.boot.storage.event.StorageDeletedEvent;
import io.github.atengk.boot.storage.event.StorageUploadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 电子合同与数字资产存储生命周期安全审计监听器
 * 演示通过 Spring 原生 @EventListener 监听 StorageUploadedEvent 与 StorageDeletedEvent 实现异步无侵入审计
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Slf4j
@Component
public class SampleStorageEventListener {

    private final AtomicInteger uploadCount = new AtomicInteger();
    private final AtomicInteger deleteCount = new AtomicInteger();
    private final AtomicReference<String> lastUploadedPath = new AtomicReference<>();
    private final AtomicReference<String> lastDeletedPath = new AtomicReference<>();

    @EventListener
    public void onUploaded(StorageUploadedEvent event) {
        log.info("[SampleStorageEventListener] 接收到文件上传审计事件: source={}, path={}, size={}, contentType={}",
                event.getSourceKey(), event.getPath(), event.getSize(), event.getContentType());
        uploadCount.incrementAndGet();
        lastUploadedPath.set(event.getPath());
    }

    @EventListener
    public void onDeleted(StorageDeletedEvent event) {
        log.info("[SampleStorageEventListener] 接收到文件删除审计事件: source={}, path={}",
                event.getSourceKey(), event.getPath());
        deleteCount.incrementAndGet();
        lastDeletedPath.set(event.getPath());
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
     * 获取最近一次上传的对象路径
     *
     * @return 最近上传路径
     */
    public String getLastUploadedPath() {
        return lastUploadedPath.get();
    }

    /**
     * 获取最近一次删除的对象路径
     *
     * @return 最近删除路径
     */
    public String getLastDeletedPath() {
        return lastDeletedPath.get();
    }

    /**
     * 重置计数器与状态快照（供单测生命周期使用）
     */
    public void reset() {
        uploadCount.set(0);
        deleteCount.set(0);
        lastUploadedPath.set(null);
        lastDeletedPath.set(null);
    }
}
