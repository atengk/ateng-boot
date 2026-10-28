package io.github.atengk.boot.sample.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Core 样例树形节点传输模型
 * 用于验证 TreeUtil 树形组装、森林构建与递归剪枝过滤
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleMenuNodeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 节点 ID
     */
    private Long id;

    /**
     * 父节点 ID
     */
    private Long parentId;

    /**
     * 菜单名称
     */
    private String name;

    /**
     * 路由路径
     */
    private String path;

    /**
     * 排序值
     */
    private Integer sort;

    /**
     * 子节点集合
     */
    private List<SampleMenuNodeDTO> children;

    public SampleMenuNodeDTO(Long id, Long parentId, String name, String path, Integer sort) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.path = path;
        this.sort = sort;
    }
}
