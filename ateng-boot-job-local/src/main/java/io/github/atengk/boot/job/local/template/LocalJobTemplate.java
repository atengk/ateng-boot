package io.github.atengk.boot.job.local.template;

import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobExecutionRecord;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单机定时任务统一操作门面接口
 * <p>
 * 提供对单机受管任务的动态增删、启停、周期变更、手动触发与状态巡检等完备对称操作。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
public interface LocalJobTemplate {

    /**
     * 暂停指定任务的周期性调度
     *
     * @param jobName 任务名称
     * @return 是否暂停成功（任务不存在返回 false）
     */
    boolean pause(String jobName);

    /**
     * 恢复指定任务的周期性调度
     *
     * @param jobName 任务名称
     * @return 是否恢复成功（任务不存在或未处于 PAUSED 状态返回 false）
     */
    boolean resume(String jobName);

    /**
     * 手动立即触发执行一次指定任务（无传参）
     *
     * @param jobName 任务名称
     * @return 执行产物结果
     */
    default JobResult trigger(String jobName) {
        return trigger(jobName, Collections.emptyMap());
    }

    /**
     * 手动立即触发执行一次指定任务（携带自定义业务参数）
     *
     * @param jobName 任务名称
     * @param params  本次调度的自定义业务参数
     * @return 执行产物结果
     */
    JobResult trigger(String jobName, Map<String, Object> params);

    /**
     * 动态修改指定任务的 Cron 周期表达式并立即重新调度
     *
     * @param jobName        任务名称
     * @param cronExpression 新的标准 Cron 表达式
     * @return 是否更新并重新调度成功
     */
    boolean updateCron(String jobName, String cronExpression);

    /**
     * 编程式注册一个受管任务
     *
     * @param definition 任务定义元数据
     * @param handler    任务执行处理器
     * @return 是否注册成功
     */
    boolean register(JobDefinition definition, JobHandler handler);

    /**
     * 极简便捷注册一个 Cron 任务
     *
     * @param jobName  任务名称
     * @param cron     Cron 表达式
     * @param runnable 任务逻辑
     * @return 是否注册成功
     */
    boolean registerCron(String jobName, String cron, Runnable runnable);

    /**
     * 极简便捷注册一个固定频率调度任务
     *
     * @param jobName   任务名称
     * @param fixedRate 固定频率时长
     * @param runnable  任务逻辑
     * @return 是否注册成功
     */
    boolean registerFixedRate(String jobName, Duration fixedRate, Runnable runnable);

    /**
     * 极简便捷注册一个固定延迟调度任务
     *
     * @param jobName    任务名称
     * @param fixedDelay 固定延迟时长
     * @param runnable   任务逻辑
     * @return 是否注册成功
     */
    boolean registerFixedDelay(String jobName, Duration fixedDelay, Runnable runnable);

    /**
     * 注销并移除指定任务
     *
     * @param jobName 任务名称
     * @return 是否成功注销并移除
     */
    boolean remove(String jobName);

    /**
     * 获取单个任务的运行时状态详情
     *
     * @param jobName 任务名称
     * @return 任务详情（不存在返回 null）
     */
    JobInfo getJob(String jobName);

    /**
     * 列出所有受管定时任务信息清单
     *
     * @return 任务信息列表（保证非 null 空安全）
     */
    List<JobInfo> listJobs();

    /**
     * 获取指定任务最近的执行历史记录清单
     *
     * @param jobName 任务名称
     * @param limit   最大查询条数
     * @return 执行审计记录列表（保证非 null 空安全）
     */
    List<JobExecutionRecord> listRecentLogs(String jobName, int limit);
}
