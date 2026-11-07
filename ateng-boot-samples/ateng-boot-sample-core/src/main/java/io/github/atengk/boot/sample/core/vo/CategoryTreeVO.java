package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 商品类目树构建与全景导航视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 完整多层级类目树
     */
    @Builder.Default
    private List<CategoryNodeVO> categoryTree = Collections.emptyList();

    /**
     * 关键词递归剪枝过滤后的类目树（保留命中节点及其祖先链路）
     */
    @Builder.Default
    private List<CategoryNodeVO> filteredTree = Collections.emptyList();

    /**
     * 深度优先先序遍历展平后的全量类目列表
     */
    @Builder.Default
    private List<CategoryNodeVO> flattenedCategories = Collections.emptyList();

    /**
     * 从顶级类目到目标节点的全链路面包屑导航路径
     */
    @Builder.Default
    private List<CategoryNodeVO> breadcrumbPath = Collections.emptyList();

    /**
     * 终端叶子节点类目列表
     */
    @Builder.Default
    private List<CategoryNodeVO> leafCategories = Collections.emptyList();

    /**
     * 类目总节点数
     */
    private Integer totalCount;

    /**
     * 终端叶子节点总数
     */
    private Integer leafCount;
}
