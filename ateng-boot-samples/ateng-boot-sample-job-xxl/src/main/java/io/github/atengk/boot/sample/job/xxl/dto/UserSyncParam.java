package io.github.atengk.boot.sample.job.xxl.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户同步任务强类型入参 DTO
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
public class UserSyncParam implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务同步批次号
     */
    @NotBlank(message = "同步批次号 batchNo 不能为空")
    private String batchNo;

    /**
     * 同步限制批大小
     */
    @NotNull(message = "批大小 batchSize 不能为空")
    @Min(value = 1, message = "批大小不能小于 1")
    @Max(value = 1000, message = "单批同步上限不能超过 1000")
    private Integer batchSize;

    /**
     * 目标租户标识
     */
    private String tenantId;
}
