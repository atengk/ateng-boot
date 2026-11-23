package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 多模态文档详情响应视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文档唯一标识 ID
     */
    private Long id;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 概要信息
     */
    private String summary;

    /**
     * 语义稠密特征向量
     */
    private List<Float> denseVector;

    /**
     * 关键词稀疏特征向量
     */
    private Map<Long, Float> sparseVector;

    /**
     * 动态扩展属性 ($meta 动态列)
     */
    private Map<String, Object> extra;
}
