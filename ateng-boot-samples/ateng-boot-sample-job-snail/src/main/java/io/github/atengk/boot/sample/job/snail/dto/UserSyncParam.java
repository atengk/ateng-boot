package io.github.atengk.boot.sample.job.snail.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户同步调度任务参数 DTO
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSyncParam implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 同步批次标识
     */
    @NotBlank(message = "同步批次标识不能为空")
    private String batchNo;

    /**
     * 单批同步数量
     */
    @NotNull(message = "单批同步数量不能为空")
    @Min(value = 1, message = "单批同步数量必须大于0")
    private Integer batchSize;

    /**
     * 部门过滤标识
     */
    private String departmentId;
}
