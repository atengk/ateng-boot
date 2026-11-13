package io.github.atengk.boot.excel.async;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步导出任务中心内部公共辅助工具
 * <p>
 * 收敛任务终态判断、进度百分比钳制与受管线程池优雅停机逻辑，消除内存与 Redis 双实现之间的重复代码。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
final class AsyncExportTaskSupport {

    private AsyncExportTaskSupport() {
    }

    static boolean isTerminalStatus(ExportTaskStatus status) {
        return status == ExportTaskStatus.SUCCESS
                || status == ExportTaskStatus.FAILED
                || status == ExportTaskStatus.CANCELLED;
    }

    static int clampInProgressPercent(int progress) {
        return Math.max(0, Math.min(99, progress));
    }

    static void shutdownExecutorGracefully(ExecutorService executor, long timeoutSeconds) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
