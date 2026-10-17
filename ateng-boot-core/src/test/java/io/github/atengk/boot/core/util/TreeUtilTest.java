package io.github.atengk.boot.core.util;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用树构建工具单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("通用树构建工具单测")
class TreeUtilTest {

    @Getter
    @Setter
    static class MenuNode {
        private Long id;
        private Long parentId;
        private String name;
        private List<MenuNode> children;

        public MenuNode(Long id, Long parentId, String name) {
            this.id = id;
            this.parentId = parentId;
            this.name = name;
        }
    }

    @Test
    @DisplayName("多级树结构构建与拓扑级联验证")
    void shouldBuildMultiLevelTreeSuccessfully() {
        MenuNode r1 = new MenuNode(1L, 0L, "系统管理");
        MenuNode r2 = new MenuNode(2L, 0L, "运维中心");
        MenuNode c11 = new MenuNode(11L, 1L, "用户管理");
        MenuNode c12 = new MenuNode(12L, 1L, "角色管理");
        MenuNode c21 = new MenuNode(21L, 2L, "日志审计");
        MenuNode l111 = new MenuNode(111L, 11L, "新增用户按钮");

        List<MenuNode> list = Arrays.asList(r1, r2, c11, c12, c21, l111);

        List<MenuNode> tree = TreeUtil.build(
                list,
                0L,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );

        assertEquals(2, tree.size());
        assertEquals("系统管理", tree.get(0).getName());
        assertEquals(2, tree.get(0).getChildren().size());

        MenuNode userManage = tree.get(0).getChildren().get(0);
        assertEquals("用户管理", userManage.getName());
        assertEquals(1, userManage.getChildren().size());
        assertEquals("新增用户按钮", userManage.getChildren().get(0).getName());

        // 无子节点的叶子节点被赋予空集合而非 null
        MenuNode roleManage = tree.get(0).getChildren().get(1);
        assertNotNull(roleManage.getChildren());
        assertTrue(roleManage.getChildren().isEmpty());
    }

    @Test
    @DisplayName("根节点 parentId 为 null 时的树形装配")
    void shouldBuildTreeWithNullRootParentId() {
        MenuNode r1 = new MenuNode(1L, null, "根节点");
        MenuNode c1 = new MenuNode(2L, 1L, "子节点");

        List<MenuNode> tree = TreeUtil.build(
                Arrays.asList(r1, c1),
                (Long) null,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );

        assertEquals(1, tree.size());
        assertEquals(1L, tree.get(0).getId());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals(2L, tree.get(0).getChildren().get(0).getId());
    }

    @Test
    @DisplayName("空安全防御：空列表或 null 返回空集合")
    void shouldHandleEmptyAndNullDefensively() {
        List<MenuNode> emptyResult1 = TreeUtil.build(
                null, 0L, MenuNode::getId, MenuNode::getParentId, MenuNode::setChildren
        );
        assertTrue(emptyResult1.isEmpty());

        List<MenuNode> emptyResult2 = TreeUtil.build(
                Collections.emptyList(), 0L, MenuNode::getId, MenuNode::getParentId, MenuNode::setChildren
        );
        assertTrue(emptyResult2.isEmpty());

        assertThrows(NullPointerException.class, () -> TreeUtil.build(
                List.of(new MenuNode(1L, 0L, "A")), 0L, null, MenuNode::getParentId, MenuNode::setChildren
        ));
    }

    @Test
    @DisplayName("O(N) 线性规模大规模节点构建性能验证")
    void shouldBuildLargeTreeInLinearTime() {
        List<MenuNode> largeList = new ArrayList<>();
        // 创建 100 个一级分类，每个一级分类下包含 99 个子项
        for (long i = 1; i <= 100; i++) {
            largeList.add(new MenuNode(i, 0L, "Category-" + i));
            for (long j = 1; j <= 99; j++) {
                long childId = i * 1000 + j;
                largeList.add(new MenuNode(childId, i, "Item-" + childId));
            }
        }

        long start = System.currentTimeMillis();
        List<MenuNode> tree = TreeUtil.build(
                largeList,
                0L,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );
        long duration = System.currentTimeMillis() - start;

        assertEquals(100, tree.size());
        assertEquals(99, tree.get(0).getChildren().size());
        // 10,000 节点线性构建耗时应在合理阈值内（放宽至 3000ms 适配多核 CI 虚拟环境抖动）
        assertTrue(duration < 3000, "10,000 节点构建耗时应小于 3000ms，实际耗时: " + duration + "ms");
    }

    @Test
    @DisplayName("多根谓词成林：支持非固定 parentId 的多根森林装配")
    void shouldBuildForestUsingRootPredicate() {
        MenuNode r1 = new MenuNode(1L, null, "Root-1");
        MenuNode r2 = new MenuNode(2L, -1L, "Root-2");
        MenuNode c1 = new MenuNode(11L, 1L, "Child-1-1");
        MenuNode c2 = new MenuNode(21L, 2L, "Child-2-1");

        List<MenuNode> list = List.of(r1, r2, c1, c2);

        // 1. 验证 build 谓词重载（通过显式类型参数调用，满足契约签名）
        java.util.function.Predicate<MenuNode> rootPredicate = node -> node.getParentId() == null || node.getParentId() < 0;
        List<MenuNode> forest = TreeUtil.<MenuNode, Long>build(
                list,
                rootPredicate,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );

        assertEquals(2, forest.size());
        assertEquals("Root-1", forest.get(0).getName());
        assertEquals(1, forest.get(0).getChildren().size());
        assertEquals("Child-1-1", forest.get(0).getChildren().get(0).getName());

        assertEquals("Root-2", forest.get(1).getName());
        assertEquals(1, forest.get(1).getChildren().size());
        assertEquals("Child-2-1", forest.get(1).getChildren().get(0).getName());

        // 2. 验证 buildForest 便捷别名
        List<MenuNode> forestFromAlias = TreeUtil.buildForest(
                list,
                node -> node.getParentId() == null || node.getParentId() < 0,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );
        assertEquals(2, forestFromAlias.size());
    }

    @Test
    @DisplayName("递归剪枝过滤：保留命中节点及其祖先链路，剔除未命中分支")
    void shouldFilterTreeWithPruning() {
        MenuNode r1 = new MenuNode(1L, 0L, "系统管理");
        MenuNode c11 = new MenuNode(11L, 1L, "用户管理");
        MenuNode c12 = new MenuNode(12L, 1L, "角色管理");
        MenuNode l111 = new MenuNode(111L, 11L, "用户导出");
        MenuNode l112 = new MenuNode(112L, 11L, "用户导入");

        MenuNode r2 = new MenuNode(2L, 0L, "资产中心");
        MenuNode c21 = new MenuNode(21L, 2L, "服务器列表");

        List<MenuNode> tree = TreeUtil.build(
                List.of(r1, c11, c12, l111, l112, r2, c21),
                0L,
                MenuNode::getId,
                MenuNode::getParentId,
                MenuNode::setChildren
        );

        // 检索关键词："导出"
        List<MenuNode> filtered = TreeUtil.filter(
                tree,
                node -> node.getName().contains("导出"),
                MenuNode::getChildren,
                MenuNode::setChildren
        );

        // 仅保留系统管理 -> 用户管理 -> 用户导出，资产中心及角色管理均被剔除
        assertEquals(1, filtered.size());
        assertEquals("系统管理", filtered.get(0).getName());
        assertEquals(1, filtered.get(0).getChildren().size());

        MenuNode userManage = filtered.get(0).getChildren().get(0);
        assertEquals("用户管理", userManage.getName());
        assertEquals(1, userManage.getChildren().size());
        assertEquals("用户导出", userManage.getChildren().get(0).getName());
        assertTrue(userManage.getChildren().get(0).getChildren().isEmpty());
    }

    @Test
    @DisplayName("剪枝过滤边界与空安全：无匹配时返回空集合")
    void shouldHandleEmptyAndNoMatchDefensivelyInFilter() {
        List<MenuNode> emptyList = TreeUtil.filter(
                Collections.emptyList(),
                node -> true,
                MenuNode::getChildren,
                MenuNode::setChildren
        );
        assertTrue(emptyList.isEmpty());

        MenuNode root = new MenuNode(1L, 0L, "Hello");
        root.setChildren(Collections.emptyList());
        List<MenuNode> noMatch = TreeUtil.filter(
                List.of(root),
                node -> node.getName().equals("NotFound"),
                MenuNode::getChildren,
                MenuNode::setChildren
        );
        assertTrue(noMatch.isEmpty());
    }
}
