package io.github.atengk.boot.sample.job.snail.dto;

import com.aizuda.snailjob.model.request.base.JobRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调度中心任务新增/更新请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminJobSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int TRIGGER_TYPE_CRON = 1;
    public static final int TRIGGER_TYPE_FIXED_RATE = 2;
    public static final int TRIGGER_TYPE_FIXED_DELAY = 3;
    public static final int ROUTE_KEY_ROUND_ROBIN = 1;
    public static final int ROUTE_KEY_RANDOM = 2;
    public static final int ROUTE_KEY_BROADCAST = 3;

    /**
     * 调度任务 ID (更新任务时必填)
     */
    @Min(value = 1, message = "任务 ID 必须大于 0")
    private Long id;

    /**
     * 任务名称
     */
    @NotBlank(message = "任务名称不能为空")
    private String jobName;

    /**
     * 执行器名称
     */
    @NotBlank(message = "执行器名称不能为空")
    private String executorInfo;

    /**
     * 触发类型 ({@value #TRIGGER_TYPE_CRON}: CRON, {@value #TRIGGER_TYPE_FIXED_RATE}: FIXED_RATE, {@value #TRIGGER_TYPE_FIXED_DELAY}: FIXED_DELAY)
     */
    @Min(value = 1, message = "触发类型必须大于 0")
    private Integer triggerType;

    /**
     * 触发间隔或 Cron 表达式
     */
    @NotBlank(message = "触发间隔或Cron表达式不能为空")
    private String triggerInterval;

    /**
     * 执行器路由策略 ({@value #ROUTE_KEY_ROUND_ROBIN}: 轮询, {@value #ROUTE_KEY_RANDOM}: 随机, {@value #ROUTE_KEY_BROADCAST}: 广播)
     */
    @Min(value = 1, message = "路由策略必须大于 0")
    private Integer routeKey;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 转换为 SnailJob OpenAPI 标准任务请求实体
     *
     * @return SnailJob {@link JobRequest}
     */
    public JobRequest toJobRequest() {
        JobRequest request = new JobRequest();
        request.setId(this.id);
        request.setJobName(this.jobName);
        request.setExecutorInfo(this.executorInfo);
        request.setTriggerType(this.triggerType != null ? this.triggerType : TRIGGER_TYPE_CRON);
        request.setTriggerInterval(this.triggerInterval);
        request.setRouteKey(this.routeKey != null ? this.routeKey : ROUTE_KEY_ROUND_ROBIN);
        request.setDescription(this.description);
        return request;
    }
}
