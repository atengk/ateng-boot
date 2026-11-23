package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 物理分区单分区运维请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionOpDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标集合名称 (默认 sample_archive_log)
     */
    @Builder.Default
    private String collectionName = "sample_archive_log";

    /**
     * 目标物理分区名称
     */
    @NotBlank(message = "分区名称不能为空")
    private String partitionName;
}
