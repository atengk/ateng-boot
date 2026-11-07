package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 商品类目树节点视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 类目 ID
     */
    private Long id;

    /**
     * 父级类目 ID（0L 表示顶级类目）
     */
    private Long parentId;

    /**
     * 类目名称
     */
    private String name;

    /**
     * 排序权重
     */
    private Integer sortOrder;

    /**
     * 子类目列表
     */
    @Builder.Default
    private List<CategoryNodeVO> children = Collections.emptyList();
}
