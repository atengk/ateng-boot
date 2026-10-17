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
     * 将平面扁平列表组装为树形嵌套结构（O(N) 线性时间复杂度，支持指定父级 ID）
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
        Objects.requireNonNull(parentIdGetter, "parentIdGetter 提取函数不能为空");
        java.util.function.Predicate<T> rootPredicate = node -> Objects.equals(parentIdGetter.apply(node), rootParentId);
        return build(list, rootPredicate, idGetter, parentIdGetter, childrenSetter);
    }

    /**
     * 将平面扁平列表组装为树形嵌套结构（O(N) 线性时间复杂度，基于自定义谓词匹配多根节点）
     * 适用于根节点父级 ID 不唯一、非列表内父级、或需要按复杂规则判定根节点的森林装配场景
     *
     * @param list           平面节点列表
     * @param rootPredicate  根节点判定谓词（返回 true 表示该节点作为顶级根节点）
     * @param idGetter       当前节点 ID 提取函数
     * @param parentIdGetter 父节点 ID 提取函数
     * @param childrenSetter 子节点列表回写函数
     * @param <T>            节点实体类型
     * @param <ID>           节点 ID 类型
     * @return 根节点列表；入参列表为空或无匹配根节点时返回空列表（空安全）
     */
    public static <T, ID> List<T> build(
            List<T> list,
            java.util.function.Predicate<T> rootPredicate,
            Function<T, ID> idGetter,
            Function<T, ID> parentIdGetter,
            BiConsumer<T, List<T>> childrenSetter
    ) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(rootPredicate, "rootPredicate 判定谓词不能为空");
        Objects.requireNonNull(idGetter, "idGetter 提取函数不能为空");
        Objects.requireNonNull(parentIdGetter, "parentIdGetter 提取函数不能为空");
        Objects.requireNonNull(childrenSetter, "childrenSetter 回写函数不能为空");

        // 1. 第一遍扫描：按 parentId 将所有子节点归类至哈希表，保留原有相对次序（跳过 null 脏数据）
        Map<ID, List<T>> parentChildrenMap = new LinkedHashMap<>();
        for (T node : list) {
            if (node == null) {
                continue;
            }
            ID parentId = parentIdGetter.apply(node);
            parentChildrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }

        // 2. 第二遍扫描：为每个节点精准挂载其对应的子节点列表（无子节点时回写空集合避免 NPE）
        for (T node : list) {
            if (node == null) {
                continue;
            }
            ID id = idGetter.apply(node);
            List<T> children = parentChildrenMap.get(id);
            childrenSetter.accept(node, children != null ? children : Collections.emptyList());
        }

        // 3. 提取根节点集合
        List<T> roots = new ArrayList<>();
        for (T node : list) {
            if (node != null && rootPredicate.test(node)) {
                roots.add(node);
            }
        }
        return roots;
    }

    /**
     * 将平面扁平列表组装为树形嵌套结构（build 谓词重载的无歧义别名）
     *
     * @param list           平面节点列表
     * @param rootPredicate  根节点判定谓词
     * @param idGetter       当前节点 ID 提取函数
     * @param parentIdGetter 父节点 ID 提取函数
     * @param childrenSetter 子节点列表回写函数
     * @param <T>            节点实体类型
     * @param <ID>           节点 ID 类型
     * @return 根节点列表
     */
    public static <T, ID> List<T> buildForest(
            List<T> list,
            java.util.function.Predicate<T> rootPredicate,
            Function<T, ID> idGetter,
            Function<T, ID> parentIdGetter,
            BiConsumer<T, List<T>> childrenSetter
    ) {
        return build(list, rootPredicate, idGetter, parentIdGetter, childrenSetter);
    }

    /**
     * 递归剪枝过滤树形结构
     * 若某节点本身匹配谓词，或其任意后代节点存在匹配，则保留该节点与通往匹配子节点的祖先链路；其余未匹配节点剔除
     *
     * @param roots          根节点列表
     * @param predicate      节点匹配断言（返回 true 表示该节点需要保留）
     * @param childrenGetter 子节点集合提取函数
     * @param childrenSetter 子节点集合回写函数
     * @param <T>            节点实体类型
     * @return 剪枝过滤后的根节点集合；无匹配节点时返回空列表（空安全）
     */
    public static <T> List<T> filter(
            List<T> roots,
            java.util.function.Predicate<T> predicate,
            Function<T, List<T>> childrenGetter,
            BiConsumer<T, List<T>> childrenSetter
    ) {
        if (roots == null || roots.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(predicate, "predicate 断言函数不能为空");
        Objects.requireNonNull(childrenGetter, "childrenGetter 提取函数不能为空");
        Objects.requireNonNull(childrenSetter, "childrenSetter 回写函数不能为空");

        List<T> filteredRoots = new ArrayList<>();
        for (T root : roots) {
            T filteredNode = filterNode(root, predicate, childrenGetter, childrenSetter);
            if (filteredNode != null) {
                filteredRoots.add(filteredNode);
            }
        }
        return filteredRoots;
    }

    private static <T> T filterNode(
            T node,
            java.util.function.Predicate<T> predicate,
            Function<T, List<T>> childrenGetter,
            BiConsumer<T, List<T>> childrenSetter
    ) {
        if (node == null) {
            return null;
        }
        List<T> rawChildren = childrenGetter.apply(node);
        List<T> matchedChildren = new ArrayList<>();
        if (rawChildren != null && !rawChildren.isEmpty()) {
            for (T child : rawChildren) {
                T matchedChild = filterNode(child, predicate, childrenGetter, childrenSetter);
                if (matchedChild != null) {
                    matchedChildren.add(matchedChild);
                }
            }
        }

        boolean selfMatches = predicate.test(node);
        if (selfMatches || !matchedChildren.isEmpty()) {
            childrenSetter.accept(node, matchedChildren);
            return node;
        }
        return null;
    }
}
