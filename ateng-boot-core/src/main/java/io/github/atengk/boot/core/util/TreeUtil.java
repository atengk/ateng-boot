package io.github.atengk.boot.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用树形结构构建工具
 * 基于纯函数式接口与哈希表两遍扫描算法，以 O(N) 线性时间复杂度实现非侵入式父子级联树装配
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class TreeUtil {

    private TreeUtil() {
    }

    /**
     * 将平面扁平列表组装为树形嵌套结构（O(N) 线性时间复杂度）
     *
     * @param list           平面节点列表
     * @param rootParentId   根节点的父节点 ID（如 null、0L、0 或 -1 等）
     * @param idGetter       当前节点 ID 提取函数
     * @param parentIdGetter 父节点 ID 提取函数
     * @param childrenSetter 子节点列表回写函数
     * @param <T>            节点实体类型
     * @param <ID>           节点 ID 类型
     * @return 根节点列表；入参列表为空或无匹配根节点时返回空列表（空安全）
     */
    public static <T, ID> List<T> build(
            List<T> list,
            ID rootParentId,
            Function<T, ID> idGetter,
            Function<T, ID> parentIdGetter,
            BiConsumer<T, List<T>> childrenSetter
    ) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(idGetter, "idGetter 提取函数不能为空");
        Objects.requireNonNull(parentIdGetter, "parentIdGetter 提取函数不能为空");
        Objects.requireNonNull(childrenSetter, "childrenSetter 回写函数不能为空");

        // 1. 第一遍扫描：按 parentId 将所有子节点归类至哈希表，保留原有相对次序
        Map<ID, List<T>> parentChildrenMap = new LinkedHashMap<>();
        for (T node : list) {
            ID parentId = parentIdGetter.apply(node);
            parentChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }

        // 2. 第二遍扫描：为每个节点精准挂载其对应的子节点列表（无子节点时回写空集合避免 NPE）
        for (T node : list) {
            ID id = idGetter.apply(node);
            List<T> children = parentChildrenMap.get(id);
            childrenSetter.accept(node, children != null ? children : Collections.emptyList());
        }

        // 3. 提取根节点集合
        List<T> roots = parentChildrenMap.get(rootParentId);
        return roots != null ? roots : Collections.emptyList();
    }
}
