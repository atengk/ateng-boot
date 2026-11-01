package io.github.atengk.boot.sample.job.local.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 历史数据冷归档业务入参传输对象
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchivingJobParam implements Serializable {

    /**
     * 目标业务表名
     */
    @NotBlank(message = "业务表名不能为空")
    private String tableName;

    /**
     * 归档数据保留天数
     */
    @Min(value = 1, message = "归档保留天数必须大于0")
    @Builder.Default
    private int retentionDays = 30;

    /**
     * 单批次归档行数上限
     */
    @Min(value = 1, message = "单批次处理容量必须大于0")
    @Builder.Default
    private int batchSize = 1000;

    /**
     * 是否仅试运行演练（不产生物理删除/归档写入）
     */
    private boolean dryRun;
}
