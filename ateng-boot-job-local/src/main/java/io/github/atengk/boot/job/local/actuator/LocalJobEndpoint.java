package io.github.atengk.boot.job.local.actuator;

import io.github.atengk.boot.job.local.model.JobInfo;
import io.github.atengk.boot.job.local.model.JobResult;
import io.github.atengk.boot.job.local.template.LocalJobTemplate;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单机定时任务 Actuator 生产级运维管控端点
 * <p>
 * 暴露路径：{@code /actuator/localjobs}
 * 支持巡检全部任务清单、单个任务详情与执行历史审计日志、以及动态立即触发、暂停与恢复。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Endpoint(id = "localjobs")
public class LocalJobEndpoint {

    private final LocalJobTemplate jobTemplate;

    public LocalJobEndpoint(LocalJobTemplate jobTemplate) {
        this.jobTemplate = jobTemplate;
    }

    /**
     * 巡检所有受管定时任务清单
     * <p>GET /actuator/localjobs</p>
     *
     * @return 任务清单摘要
     */
    @ReadOperation
    public Map<String, Object> listJobs() {
        List<JobInfo> jobs = jobTemplate.listJobs();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("total", jobs.size());
        response.put("jobs", jobs);
        return response;
    }

    /**
     * 查询指定任务详情与最近执行历史日志
     * <p>GET /actuator/localjobs/{jobName}</p>
     *
     * @param jobName 任务标识
     * @return 任务详情与历史日志
     */
    @ReadOperation
    public Map<String, Object> getJob(@Selector String jobName) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (jobName == null || jobName.isBlank()) {
            response.put("status", "BAD_REQUEST");
            response.put("message", "jobName must not be empty");
            return response;
        }

        JobInfo info = jobTemplate.getJob(jobName.trim());
        if (info == null) {
            response.put("status", "NOT_FOUND");
            response.put("message", "Job [" + jobName.trim() + "] not found");
            return response;
        }

        response.put("status", "OK");
        response.put("job", info);
        response.put("recentLogs", jobTemplate.listRecentLogs(jobName.trim(), 10));
        return response;
    }

    /**
     * 对指定任务执行动态运维操作
     * <p>POST /actuator/localjobs/{jobName}</p>
     *
     * @param jobName 任务名称
     * @param action  操作指令：trigger (立即触发) / pause (暂停) / resume (恢复)
     * @param params  当 action 为 trigger 时支持传入自定义业务参数
     * @return 操作结果
     */
    @WriteOperation
    public Map<String, Object> executeAction(@Selector String jobName, String action, @Nullable Map<String, Object> params) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobName", jobName);
        response.put("action", action);

        if (jobName == null || jobName.isBlank()) {
            response.put("success", false);
            response.put("message", "jobName must not be empty");
            return response;
        }

        if (action == null || action.isBlank()) {
            response.put("success", false);
            response.put("message", "Action must not be empty. Supported actions: trigger, pause, resume");
            return response;
        }

        String normalizedAction = action.trim().toLowerCase();
        String normalizedJobName = jobName.trim();

        switch (normalizedAction) {
            case "trigger":
                JobResult result = jobTemplate.trigger(normalizedJobName, params != null ? params : Collections.emptyMap());
                response.put("success", result.isSuccess());
                response.put("message", result.getMessage());
                if (result.getData() != null) {
                    response.put("data", result.getData());
                }
                return response;

            case "pause":
                boolean paused = jobTemplate.pause(normalizedJobName);
                response.put("success", paused);
                response.put("message", paused ? "Job [" + normalizedJobName + "] paused successfully"
                        : "Job [" + normalizedJobName + "] not found or failed to pause");
                return response;

            case "resume":
                boolean resumed = jobTemplate.resume(normalizedJobName);
                response.put("success", resumed);
                response.put("message", resumed ? "Job [" + normalizedJobName + "] resumed successfully"
                        : "Job [" + normalizedJobName + "] not found or failed to resume");
                return response;

            default:
                response.put("success", false);
                response.put("message", "Unsupported action: " + action + ". Supported actions: trigger, pause, resume");
                return response;
        }
    }
}
