package io.github.atengk.boot.job.local.registry;

import io.github.atengk.boot.job.local.enums.JobStatus;
import io.github.atengk.boot.job.local.enums.TriggerType;
import io.github.atengk.boot.job.local.handler.JobHandler;
import io.github.atengk.boot.job.local.model.JobDefinition;
import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 单机定时任务注册中心与调度运行时持有者
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class LocalJobRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LocalJobRegistry.class);

    private final ConcurrentMap<String, ScheduledJobTask> taskMap = new ConcurrentHashMap<>();
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AsyncTaskExecutor workerExecutor;

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler) {
        this(taskScheduler, null);
    }

    public LocalJobRegistry(ThreadPoolTaskScheduler taskScheduler, AsyncTaskExecutor workerExecutor) {
        this.taskScheduler = taskScheduler;
        this.workerExecutor = workerExecutor;
    }

    /**
     * 注册并调度一个任务
     *
     * @param definition 任务定义
     * @param handler    任务处理器
     * @return 注册后的受管任务
     */
    public ScheduledJobTask register(JobDefinition definition, JobHandler handler) {
        String jobName = definition.getJobName();
        if (taskMap.containsKey(jobName)) {
            throw new IllegalArgumentException("Duplicate job registration detected for job name: " + jobName);
        }

        ScheduledJobTask task = new ScheduledJobTask(definition, handler, taskScheduler, workerExecutor);
        if (task.getStatus() == JobStatus.RUNNING && taskScheduler != null) {
            scheduleTask(task);
        }
        taskMap.put(jobName, task);
        log.info("Registered local job [{}] with trigger type [{}]", jobName, definition.getTriggerType());
        return task;
    }

    /**
     * 将任务调度到底层线程调度器
     */
    protected void scheduleTask(ScheduledJobTask task) {
        Trigger trigger = task.getTrigger();
        if (trigger != null && taskScheduler != null) {
            ScheduledFuture<?> future = taskScheduler.schedule(task, trigger);
            task.setScheduledFuture(future);
            task.setStatus(JobStatus.RUNNING);
            log.debug("Scheduled job [{}] onto task scheduler", task.getDefinition().getJobName());
        }
    }

    /**
     * 取消底层线程调度
     */
    protected void cancelSchedule(ScheduledJobTask task) {
        ScheduledFuture<?> future = task.getScheduledFuture();
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            task.setScheduledFuture(null);
        }
    }

    /**
     * 暂停任务
     *
     * @param jobName 任务名称
     * @return 是否暂停成功
     */
    public boolean pause(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        cancelSchedule(task);
        task.setStatus(JobStatus.PAUSED);
        log.info("Paused local job [{}]", jobName);
        return true;
    }

    /**
     * 恢复任务调度
     *
     * @param jobName 任务名称
     * @return 是否恢复成功
     */
    public boolean resume(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        if (task.getStatus() == JobStatus.PAUSED) {
            scheduleTask(task);
            log.info("Resumed local job [{}]", jobName);
            return true;
        }
        return false;
    }

    /**
     * 立即手动触发执行一次
     *
     * @param jobName 任务名称
     * @param params  业务入参
     * @return 执行产物结果
     */
    public JobResult trigger(String jobName, Map<String, Object> params) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return JobResult.failure("Job not found: " + jobName);
        }
        return task.triggerOnce(params);
    }

    /**
     * 动态修改 Cron 周期表达式
     *
     * @param jobName 任务名称
     * @param newCron 新的 Cron 表达式
     * @return 是否更新并重新调度成功
     */
    public boolean updateCron(String jobName, String newCron) {
        ScheduledJobTask task = taskMap.get(jobName);
        if (task == null) {
            return false;
        }
        cancelSchedule(task);
        task.getDefinition().setCron(newCron);
        task.getDefinition().setTriggerType(TriggerType.CRON);
        task.setTrigger(new CronTrigger(newCron));
        if (task.getStatus() == JobStatus.RUNNING) {
            scheduleTask(task);
        }
        log.info("Updated Cron expression for local job [{}] to [{}]", jobName, newCron);
        return true;
    }

    /**
     * 注销并移除任务
     *
     * @param jobName 任务名称
     * @return 是否注销成功
     */
    public boolean remove(String jobName) {
        ScheduledJobTask task = taskMap.remove(jobName);
        if (task != null) {
            cancelSchedule(task);
            task.setStatus(JobStatus.STOPPED);
            log.info("Removed local job [{}]", jobName);
            return true;
        }
        return false;
    }

    /**
     * 获取单个任务信息视图
     *
     * @param jobName 任务名称
     * @return JobInfo 或 null
     */
    public JobInfo getJobInfo(String jobName) {
        ScheduledJobTask task = taskMap.get(jobName);
        return task != null ? task.toJobInfo() : null;
    }

    /**
     * 获取所有任务信息清单
     *
     * @return 任务清单列表
     */
    public List<JobInfo> listJobInfos() {
        List<JobInfo> list = new ArrayList<>(taskMap.size());
        for (ScheduledJobTask task : taskMap.values()) {
            list.add(task.toJobInfo());
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 获取所有任务包装器
     *
     * @return 任务 Map
     */
    public Map<String, ScheduledJobTask> getTaskMap() {
        return Collections.unmodifiableMap(taskMap);
    }

    @Override
    public void destroy() {
        log.info("Shutting down LocalJobRegistry, canceling all active scheduled jobs (count: {})", taskMap.size());
        for (ScheduledJobTask task : taskMap.values()) {
            cancelSchedule(task);
            task.setStatus(JobStatus.STOPPED);
        }
        taskMap.clear();
    }
}
