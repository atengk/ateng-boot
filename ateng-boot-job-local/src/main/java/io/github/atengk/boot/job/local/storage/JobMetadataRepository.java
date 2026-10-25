package io.github.atengk.boot.job.local.storage;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.model.JobDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 任务元数据持久化与配置仲裁存储 SPI
 * <p>
 * 负责任务静态定义、动态调度周期与启停生命周期状态的存储与检索，支持内存与外部数据库持久化扩展。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface JobMetadataRepository {

    /**
     * 保存或更新任务定义元数据
     *
     * @param definition 任务静态定义对象
     */
    void save(JobDefinition definition);

    /**
     * 根据任务名称查找已持久化的任务定义
     *
     * @param jobName 任务唯一标识名称
     * @return 任务定义 Optional（若不存在返回 Optional.empty()）
     */
    Optional<JobDefinition> findByJobName(String jobName);

    /**
     * 更新任务运行时状态（如 RUNNING / PAUSED / STOPPED）
     *
     * @param jobName 任务名称
     * @param status  目标状态
     * @return 是否更新成功
     */
    boolean updateStatus(String jobName, JobStatus status);

    /**
     * 动态更新任务 Cron 表达式
     *
     * @param jobName 任务名称
     * @param newCron 新的 Cron 表达式
     * @return 是否更新成功
     */
    boolean updateCron(String jobName, String newCron);

    /**
     * 查询所有已持久化的任务定义清单
     *
     * @return 任务定义列表（保证空安全）
     */
    List<JobDefinition> findAll();

    /**
     * 删除指定任务元数据
     *
     * @param jobName 任务名称
     * @return 是否删除成功
     */
    boolean delete(String jobName);
}
