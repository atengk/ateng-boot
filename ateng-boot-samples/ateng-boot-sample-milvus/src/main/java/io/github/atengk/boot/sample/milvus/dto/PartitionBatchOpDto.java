package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 物理分区批量加载/释放运维请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionBatchOpDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标集合名称 (默认 sample_archive_log)
     */
    @Builder.Default
    private String collectionName = "sample_archive_log";

    /**
     * 目标物理分区名称列表
     */
    @NotEmpty(message = "分区名称列表不能为空")
    private List<@NotBlank(message = "分区名称元素不能为空") String> partitionNames;
}
