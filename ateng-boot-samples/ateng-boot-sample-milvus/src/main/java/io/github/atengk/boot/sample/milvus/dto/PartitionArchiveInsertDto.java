package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 时序归档日志按分区写入请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionArchiveInsertDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标物理分区名称 (如 part_202612)
     */
    @NotBlank(message = "目标物理分区名称不能为空")
    private String partitionName;

    /**
     * 归档日志主键 ID
     */
    @NotNull(message = "日志ID不能为空")
    private Long id;

    /**
     * 归档月份标识
     */
    @NotBlank(message = "归档月份不能为空")
    private String archiveMonth;

    /**
     * 日志摘要标题
     */
    @NotBlank(message = "日志标题不能为空")
    private String title;

    /**
     * 4 维日志特征向量
     */
    @NotEmpty(message = "特征向量不能为空")
    private List<Float> vector;
}
