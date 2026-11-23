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
import java.util.Map;

/**
 * 多模态文档新增写入请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalInsertDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一主键 ID
     */
    @NotNull(message = "文档ID不能为空")
    private Long id;

    /**
     * 文档标题
     */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /**
     * 概要信息 (对应倒排索引)
     */
    private String summary;

    /**
     * 语义稠密特征向量 (4维)
     */
    @NotEmpty(message = "稠密特征向量不能为空")
    private List<Float> denseVector;

    /**
     * 关键词稀疏特征向量 (BM25 / SPLADE: termId -> weight)
     */
    private Map<Long, Float> sparseVector;

    /**
     * 动态扩展属性 ($meta 动态列)
     */
    private Map<String, Object> extra;
}
