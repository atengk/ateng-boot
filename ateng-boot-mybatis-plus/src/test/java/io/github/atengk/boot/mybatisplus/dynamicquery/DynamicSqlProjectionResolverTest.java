package io.github.atengk.boot.mybatisplus.dynamicquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多表 AST 投影解析引擎 DynamicSqlProjectionResolver 单元测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
class DynamicSqlProjectionResolverTest {

    @BeforeEach
    void setUp() {
        DynamicSqlProjectionResolver.clearCache();
    }

    @Test
    @DisplayName("测试标准多表 JOIN SQL 投影静态分析与别名归一化")
    void testResolveStandardJoinQuery() {
        String sql = "SELECT u.id, u.username, u.real_name, d.name AS dept_name, d.code AS deptCode, r.role_code " +
                "FROM sys_user u " +
                "LEFT JOIN sys_dept d ON u.dept_id = d.id " +
                "LEFT JOIN sys_user_role ur ON u.id = ur.user_id " +
                "LEFT JOIN sys_role r ON ur.role_id = r.id " +
                "WHERE u.deleted = 0";

        Map<String, String> mapping = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertNotNull(mapping);
        assertFalse(mapping.isEmpty());

        // 1. 无别名单列投影
        assertEquals("u.id", DynamicSqlProjectionResolver.findColumn(mapping, "id"));
        assertEquals("u.username", DynamicSqlProjectionResolver.findColumn(mapping, "username"));

        // 2. 无别名下划线列投影（支持驼峰与下划线互通）
        assertEquals("u.real_name", DynamicSqlProjectionResolver.findColumn(mapping, "real_name"));
        assertEquals("u.real_name", DynamicSqlProjectionResolver.findColumn(mapping, "realName"));
        assertEquals("u.real_name", DynamicSqlProjectionResolver.findColumn(mapping, "realname"));

        // 3. 显式下划线别名映射（dept_name -> d.name，支持前端传 deptName 自动自愈）
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "dept_name"));
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "deptName"));
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "deptname"));

        // 4. 显式驼峰别名映射（deptCode -> d.code，支持前端传 dept_code 自动自愈）
        assertEquals("d.code", DynamicSqlProjectionResolver.findColumn(mapping, "deptCode"));
        assertEquals("d.code", DynamicSqlProjectionResolver.findColumn(mapping, "dept_code"));
        assertEquals("d.code", DynamicSqlProjectionResolver.findColumn(mapping, "deptcode"));

        // 5. 关联表列投影
        assertEquals("r.role_code", DynamicSqlProjectionResolver.findColumn(mapping, "role_code"));
        assertEquals("r.role_code", DynamicSqlProjectionResolver.findColumn(mapping, "roleCode"));

        // 6. 不存在的列
        assertNull(DynamicSqlProjectionResolver.findColumn(mapping, "not_exist"));
    }

    @Test
    @DisplayName("测试复杂函数与计算表达式投影解析")
    void testResolveComplexFunctionAndExpression() {
        String sql = "SELECT " +
                "u.id, " +
                "COALESCE(u.status, 0) AS user_status, " +
                "CONCAT(u.first_name, ' ', u.last_name) AS full_name, " +
                "COUNT(o.id) AS order_count " +
                "FROM sys_user u " +
                "LEFT JOIN sys_order o ON u.id = o.user_id " +
                "GROUP BY u.id";

        Map<String, String> mapping = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertNotNull(mapping);

        assertEquals("COALESCE(u.status, 0)", DynamicSqlProjectionResolver.findColumn(mapping, "user_status"));
        assertEquals("COALESCE(u.status, 0)", DynamicSqlProjectionResolver.findColumn(mapping, "userStatus"));
        assertEquals("CONCAT(u.first_name, ' ', u.last_name)", DynamicSqlProjectionResolver.findColumn(mapping, "full_name"));
        assertEquals("CONCAT(u.first_name, ' ', u.last_name)", DynamicSqlProjectionResolver.findColumn(mapping, "fullName"));
        assertEquals("COUNT(o.id)", DynamicSqlProjectionResolver.findColumn(mapping, "order_count"));
        assertEquals("COUNT(o.id)", DynamicSqlProjectionResolver.findColumn(mapping, "orderCount"));
    }

    @Test
    @DisplayName("测试包含反引号与双引号转义别名的清洗")
    void testResolveQuotedAliases() {
        String sql = "SELECT u.id, d.name AS `dept_name`, r.title AS \"role_title\" " +
                "FROM sys_user u " +
                "LEFT JOIN sys_dept d ON u.dept_id = d.id " +
                "LEFT JOIN sys_role r ON u.role_id = r.id";

        Map<String, String> mapping = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertNotNull(mapping);

        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "dept_name"));
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "deptName"));
        assertEquals("r.title", DynamicSqlProjectionResolver.findColumn(mapping, "role_title"));
        assertEquals("r.title", DynamicSqlProjectionResolver.findColumn(mapping, "roleTitle"));
    }

    @Test
    @DisplayName("测试带通配符 SELECT * 与 SELECT u.* 混合投影时的弹性防御")
    void testResolveWildcardQuery() {
        String sql = "SELECT u.*, d.name AS dept_name FROM sys_user u LEFT JOIN sys_dept d ON u.dept_id = d.id";

        Map<String, String> mapping = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertNotNull(mapping);

        // 显式声明了别名的列依然可正常推导
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "dept_name"));
        assertEquals("d.name", DynamicSqlProjectionResolver.findColumn(mapping, "deptName"));

        // 通配符 * 本身无法推导具体物理列，返回 null（后续由 VO 上的 @QueryField 逃生通道接管）
        assertNull(DynamicSqlProjectionResolver.findColumn(mapping, "username"));
    }

    @Test
    @DisplayName("测试基于 StatementId 与 SQL 的多级快照缓存")
    void testCacheHitAndStatementId() {
        String statementId = "io.github.atengk.boot.sample.mapper.UserMapper.selectUserDeptPage";
        String sql = "SELECT u.id, d.name AS dept_name FROM sys_user u JOIN sys_dept d ON u.dept_id = d.id";

        assertEquals(0, DynamicSqlProjectionResolver.cacheSize());

        // 首次解析并根据 statementId 缓存
        Map<String, String> map1 = DynamicSqlProjectionResolver.resolveProjections(statementId, sql);
        assertEquals(1, DynamicSqlProjectionResolver.cacheSize());
        assertNotNull(map1);

        // 二次获取直接命中缓存（指针一致）
        Map<String, String> map2 = DynamicSqlProjectionResolver.resolveProjections(statementId, sql);
        assertSame(map1, map2);
        assertEquals(1, DynamicSqlProjectionResolver.cacheSize());

        // 基于纯 SQL 获取（若 statementId 为空）
        Map<String, String> map3 = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertEquals(2, DynamicSqlProjectionResolver.cacheSize());
        assertEquals("d.name", map3.get("deptName"));

        // 清理缓存
        DynamicSqlProjectionResolver.clearCache();
        assertEquals(0, DynamicSqlProjectionResolver.cacheSize());
    }

    @Test
    @DisplayName("测试不可变 Map 防御，杜绝外部篡改缓存")
    void testImmutableMapDefense() {
        String sql = "SELECT u.id, u.username FROM sys_user u";
        Map<String, String> mapping = DynamicSqlProjectionResolver.resolveProjections(sql);
        assertThrows(UnsupportedOperationException.class, () -> mapping.put("hack", "injected"));
    }

    @Test
    @DisplayName("测试异常与边界场景的防御性回退（Null、空字符串、非 SELECT 语句、语法错误 SQL）")
    void testDefensiveFallbacks() {
        // null 或空字符串
        assertTrue(DynamicSqlProjectionResolver.resolveProjections(null).isEmpty());
        assertTrue(DynamicSqlProjectionResolver.resolveProjections("").isEmpty());
        assertTrue(DynamicSqlProjectionResolver.resolveProjections("   ").isEmpty());

        // 非 SELECT 语句（如 UPDATE/DELETE）
        assertTrue(DynamicSqlProjectionResolver.resolveProjections("UPDATE sys_user SET status = 1 WHERE id = 1").isEmpty());
        assertTrue(DynamicSqlProjectionResolver.resolveProjections("DELETE FROM sys_user WHERE id = 1").isEmpty());

        // 语法错误 SQL，不抛异常崩溃，安全返回空 Map
        assertTrue(DynamicSqlProjectionResolver.resolveProjections("SELECT FROM WHERE INVALID").isEmpty());

        // findColumn 边界防御
        assertNull(DynamicSqlProjectionResolver.findColumn(null, "username"));
        assertNull(DynamicSqlProjectionResolver.findColumn(Map.of(), "username"));
        assertNull(DynamicSqlProjectionResolver.findColumn(Map.of("a", "b"), null));
        assertNull(DynamicSqlProjectionResolver.findColumn(Map.of("a", "b"), "   "));
    }

    @Test
    @DisplayName("测试下划线与驼峰命名互转工具方法")
    void testCamelAndUnderlineUtils() {
        assertEquals("dept_name", DynamicSqlProjectionResolver.camelToUnderline("deptName"));
        assertEquals("dept_code", DynamicSqlProjectionResolver.camelToUnderline("deptCode"));
        assertEquals("id", DynamicSqlProjectionResolver.camelToUnderline("id"));
        assertEquals("", DynamicSqlProjectionResolver.camelToUnderline(null));

        assertEquals("deptName", DynamicSqlProjectionResolver.underlineToCamel("dept_name"));
        assertEquals("deptCode", DynamicSqlProjectionResolver.underlineToCamel("dept_code"));
        assertEquals("id", DynamicSqlProjectionResolver.underlineToCamel("id"));
        assertEquals("", DynamicSqlProjectionResolver.underlineToCamel(null));
    }
}
