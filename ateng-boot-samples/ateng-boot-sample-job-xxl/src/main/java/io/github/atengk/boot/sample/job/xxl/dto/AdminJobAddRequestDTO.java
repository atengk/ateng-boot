package io.github.atengk.boot.sample.job.xxl.dto;

import com.xxl.job.core.openapi.admin.dto.JobAddRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 调度中心动态新增任务请求传输对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminJobAddRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 执行器主键 ID
     */
    @NotNull(message = "执行器组 ID (jobGroup) 不能为空")
    private Integer jobGroup;

    /**
     * 任务描述名称
     */
    @NotBlank(message = "任务名称 (name) 不能为空")
    private String name;

    /**
     * 执行器 JobHandler 标识名称
     */
    @NotBlank(message = "执行器处理器标识 (executorHandler) 不能为空")
    private String executorHandler;

    /**
     * 调度类型 (CRON、FIX_RATE 等)
     */
    @Builder.Default
    private String scheduleType = "CRON";

    /**
     * 调度配置 (如 Cron 表达式)
     */
    @Builder.Default
    private String scheduleConf = "0 0 2 * * ?";

    /**
     * 执行器参数 (可选)
     */
    private String executorParam;

    /**
     * 转换为官方 OpenAPI 请求实体
     *
     * @return 官方 JobAddRequest
     */
    public JobAddRequest toJobAddRequest() {
        JobAddRequest req = new JobAddRequest();
        req.setJobGroup(this.jobGroup);
        req.setName(this.name);
        req.setExecutorHandler(this.executorHandler);
        req.setScheduleType(this.scheduleType);
        req.setScheduleConf(this.scheduleConf);
        req.setExecutorParam(this.executorParam);
        return req;
    }
}
