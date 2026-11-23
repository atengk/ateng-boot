package io.github.atengk.boot.sample.job.xxl.dto;

import com.xxl.job.core.openapi.admin.dto.JobUpdateRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 调度中心动态更新任务请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminJobUpdateRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待更新任务主键 ID
     */
    @NotNull(message = "任务 ID (id) 不能为空")
    @Min(value = 1, message = "任务 ID (id) 必须大于 0")
    private Integer id;

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
    private String scheduleConf = "0 0 4 * * ?";

    /**
     * 执行器参数 (可选)
     */
    private String executorParam;

    /**
     * 转换并补齐官方 OpenAPI 更新请求实体必填默认值
     *
     * @return 官方 JobUpdateRequest
     */
    public JobUpdateRequest toJobUpdateRequest() {
        JobUpdateRequest req = new JobUpdateRequest();
        req.setId(this.id);
        req.setName(this.name);
        req.setAuthor("Ateng");
        req.setExecutorHandler(this.executorHandler);
        req.setScheduleType(this.scheduleType);
        req.setScheduleConf(this.scheduleConf);
        req.setExecutorParam(this.executorParam);
        req.setExecutorRouteStrategy("FIRST");
        req.setMisfireStrategy("DO_NOTHING");
        req.setExecutorBlockStrategy("SERIAL_EXECUTION");
        return req;
    }
}
