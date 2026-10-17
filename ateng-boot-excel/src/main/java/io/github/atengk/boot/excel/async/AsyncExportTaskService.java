package io.github.atengk.boot.excel.async;

import java.time.Duration;

/**
 * 异步海量 Excel 导出任务中心服务 SPI
 * <p>
 * 提供解耦 HTTP 短连接、后台异步排队、生成大报表文件、进度状态查询与过期文件清理的核心契约。
 * 默认装配单机受管线程池内存实现 {@link InMemoryAsyncExportTaskService}，并允许应用无缝扩展为分布式 Redis/MQ/OSS 实现。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public interface AsyncExportTaskService {

    /**
     * 提交一个异步导出任务
     *
     * @param taskName 任务名称或业务描述（不可为空）
     * @param executor 具体的 Excel 写出执行逻辑
     * @return 任务唯一 ID
     */
    String submit(String taskName, ExportTaskExecutor executor);

    /**
     * 根据任务 ID 获取任务当前执行状态与详情
     *
     * @param taskId 任务唯一 ID
     * @return 导出任务详情，若未查到则返回 null
     */
    ExportTask getTask(String taskId);

    /**
     * 取消指定的异步导出任务
     *
     * @param taskId 任务唯一 ID
     * @return true 若取消成功
     */
    boolean cancel(String taskId);

    /**
     * 清理已过期的导出任务记录与本地临时文件
     *
     * @param retention 临时文件最长存活保留时间
     */
    void cleanExpiredTasks(Duration retention);
}
