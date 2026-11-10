package io.github.atengk.boot.sample.mqtt.service;

import io.github.atengk.boot.sample.mqtt.dto.TelemetryInspectionRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备遥测监听消费巡检捕获器
 * 用于在 Web 控制器与被动监听器之间实现双轨回显与状态探查
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Component
public class TelemetryInspectionHolder {

    private final Map<String, CompletableFuture<TelemetryInspectionRecord>> waiters = new ConcurrentHashMap<>();
    private final Map<String, TelemetryInspectionRecord> latestByDevice = new ConcurrentHashMap<>();
    private final AtomicReference<TelemetryInspectionRecord> latestRecord = new AtomicReference<>();

    /**
     * 注册对特定设备的遥测消费等待
     *
     * @param deviceId 设备标识
     * @return 异步结果句柄
     */
    public CompletableFuture<TelemetryInspectionRecord> registerWait(String deviceId) {
        CompletableFuture<TelemetryInspectionRecord> future = new CompletableFuture<>();
        waiters.put(deviceId, future);
        return future;
    }

    /**
     * 监听器消费完成时记录并通知等待者
     *
     * @param record 消费捕获记录
     */
    public void record(TelemetryInspectionRecord record) {
        if (record == null || record.deviceId() == null) {
            return;
        }
        latestByDevice.put(record.deviceId(), record);
        latestRecord.set(record);

        CompletableFuture<TelemetryInspectionRecord> future = waiters.remove(record.deviceId());
        if (future != null && !future.isDone()) {
            future.complete(record);
        }
    }

    /**
     * 获取指定设备的最新消费记录
     *
     * @param deviceId 设备标识
     * @return 最新记录
     */
    public TelemetryInspectionRecord getLatest(String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            return latestByDevice.get(deviceId);
        }
        return latestRecord.get();
    }

    /**
     * 获取全局最新的消费记录
     *
     * @return 最新记录
     */
    public TelemetryInspectionRecord getLatestRecord() {
        return latestRecord.get();
    }

    /**
     * 清空记录与等待器
     */
    public void clear() {
        waiters.clear();
        latestByDevice.clear();
        latestRecord.set(null);
    }
}
