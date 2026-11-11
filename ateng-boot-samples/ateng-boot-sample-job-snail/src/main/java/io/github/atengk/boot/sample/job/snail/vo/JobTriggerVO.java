package io.github.atengk.boot.sample.job.snail.vo;

import com.aizuda.snailjob.common.core.enums.StatusEnum;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.helper.SnailJobTestHelper.SnailJobTestResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调度任务触发结果视图模型
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobTriggerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务名称标识
     */
    private String jobName;

    /**
     * 执行结果状态 (SUCCESS / FAILURE / SKIPPED)
     */
    private String status;

    /**
     * 执行状态码 (1: 成功, 0: 失败)
     */
    private Integer statusCode;

    /**
     * 链路追踪 TraceId
     */
    private String traceId;

    /**
     * 执行耗时 (毫秒)
     */
    private Long elapsedMs;

    /**
     * 执行响应报文或诊断摘要
     */
    private String message;

    /**
     * 根据脱网测试执行诊断结果构造标准化触发视图模型
     *
     * @param jobName 任务名称标识
     * @param result  脱网执行诊断结果
     * @return 标准 {@link JobTriggerVO}
     */
    public static JobTriggerVO from(String jobName, SnailJobTestResult result) {
        if (result == null) {
            return JobTriggerVO.builder()
                    .jobName(jobName)
                    .status("FAILURE")
                    .statusCode(StatusEnum.NO.getStatus())
                    .message("No execution result returned")
                    .build();
        }
        boolean success = result.isSuccess();
        Integer statusCode = success ? StatusEnum.YES.getStatus() : StatusEnum.NO.getStatus();
        String message = success ? "Executed" : "Failed";

        if (result.result() instanceof ExecuteResult er) {
            statusCode = er.getStatus();
            success = result.isSuccess() && StatusEnum.YES.getStatus() == er.getStatus();
            if (er.getMessage() != null && !er.getMessage().isBlank()) {
                message = er.getMessage();
            } else if (er.getResult() != null) {
                message = String.valueOf(er.getResult());
            } else {
                message = success ? "Success" : "Failed";
            }
        } else if (result.throwable() != null) {
            message = result.throwable().getMessage();
        }

        return JobTriggerVO.builder()
                .jobName(jobName)
                .status(success ? "SUCCESS" : "FAILURE")
                .statusCode(statusCode)
                .traceId(result.traceId())
                .elapsedMs(result.durationMs())
                .message(message)
                .build();
    }
}
