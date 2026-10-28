package io.github.atengk.boot.job.local.storage;

import io.github.atengk.boot.job.local.model.JobExecutionRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 任务执行历史日志存储 SPI
 * <p>
 * 负责记录任务单次执行的终态、耗时、异常及实时日志，支持内存有界队列防 OOM 与外部数据库持久化扩展。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface JobLogStorageProvider {

    /**
     * 写入一条任务执行记录
     *
     * @param record 执行记录对象
     */
    void saveLog(JobExecutionRecord record);

    /**
     * 按任务名称倒序查询最近的执行记录（最新的在前）
     *
     * @param jobName 任务名称
     * @param limit   最大返回记录条数（<=0 时使用默认保底容量）
     * @return 执行记录列表（保证空安全）
     */
    List<JobExecutionRecord> listLogs(String jobName, int limit);

    /**
     * 滚动清理过期历史日志（防持久化模式无限膨胀）
     *
     * @param expireBefore 此时间戳之前的历史记录将被清理
     * @return 实际清理的记录数
     */
    int cleanExpiredLogs(Instant expireBefore);

    /**
     * 滚动清理超出保留时长的历史日志（便捷默认方法）
     *
     * @param retentionDuration 日志最长保留时长
     * @return 实际清理的记录数
     */
    default int cleanExpiredLogs(Duration retentionDuration) {
        if (retentionDuration == null || retentionDuration.isZero() || retentionDuration.isNegative()) {
            return 0;
        }
        return cleanExpiredLogs(Instant.now().minus(retentionDuration));
    }
}
