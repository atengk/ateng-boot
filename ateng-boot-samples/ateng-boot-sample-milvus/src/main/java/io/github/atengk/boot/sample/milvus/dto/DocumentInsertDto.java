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
 * 文档插入请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInsertDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一主键 ID
     */
    @NotNull(message = "文档主键ID不能为空")
    private Long id;

    /**
     * 物理分类分区路由键
     */
    @NotBlank(message = "分类分区路由键不能为空")
    private String category;

    /**
     * SaaS 多租户标识 (可选)
     */
    private String tenantId;

    /**
     * 文档标题
     */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /**
     * 文档正文
     */
    @NotBlank(message = "正文内容不能为空")
    private String content;

    /**
     * 4 维稠密特征向量
     */
    @NotEmpty(message = "特征向量不能为空")
    private List<Float> vector;
}
