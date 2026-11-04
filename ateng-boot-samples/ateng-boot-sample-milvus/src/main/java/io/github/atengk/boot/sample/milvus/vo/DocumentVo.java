package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 文档详情响应视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一主键 ID
     */
    private Long id;

    /**
     * 分类分区路由键
     */
    private String category;

    /**
     * SaaS 多租户隔离标识
     */
    private String tenantId;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档正文
     */
    private String content;

    /**
     * 特征向量
     */
    private List<Float> vector;
}
