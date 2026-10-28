package io.github.atengk.boot.mybatisplus.dynamicsql;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部动态 SQL 元数据定义实体单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("外部动态 SQL 元数据定义实体单元测试")
class DynamicSqlDefinitionTest {

    @Test
    @DisplayName("验证标准 Builder 构建与基本属性读取")
    void testStandardBuilder() {
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("user.queryByName")
                .sqlScript("<script>SELECT * FROM sys_user WHERE name = #{name}</script>")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .description("按名称查询用户")
                .build();

        assertEquals("user.queryByName", def.getId());
        assertEquals("<script>SELECT * FROM sys_user WHERE name = #{name}</script>", def.getSqlScript());
        assertEquals(SqlCommandType.SELECT, def.getCommandType());
        assertEquals(Map.class, def.getResultType());
        assertEquals("按名称查询用户", def.getDescription());
    }

    @Test
    @DisplayName("验证智能容错包裹：含动态标签且未闭合 <script> 时自动包裹")
    void testAutoScriptWrapForDynamicTags() {
        String rawSql = "SELECT * FROM sys_user \n<where>\n <if test=\"name != null\">AND name = #{name}</if>\n</where>";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("user.queryDynamic")
                .sqlScript(rawSql)
                .commandType(SqlCommandType.SELECT)
                .autoScriptWrap(true)
                .build();

        assertTrue(def.getSqlScript().startsWith("<script>"), "自动容错应当在首部添加 <script>");
        assertTrue(def.getSqlScript().endsWith("</script>"), "自动容错应当在尾部添加 </script>");
        assertTrue(def.getSqlScript().contains("<where>"));
    }

    @Test
    @DisplayName("验证若已手动包裹 <script> 则绝不重复包裹")
    void testNoDuplicateScriptWrap() {
        String rawSql = "  <script>SELECT * FROM sys_user <where><if test=\"1==1\">AND id = 1</if></where></script>  ";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("user.queryWrapped")
                .sqlScript(rawSql)
                .autoScriptWrap(true)
                .build();

        assertEquals(rawSql.trim(), def.getSqlScript());
    }

    @Test
    @DisplayName("验证关闭 autoScriptWrap 时保持原始 SQL 不变")
    void testDisabledAutoScriptWrap() {
        String rawSql = "SELECT * FROM sys_user <where>AND id = 1</where>";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("user.queryRaw")
                .sqlScript(rawSql)
                .autoScriptWrap(false)
                .build();

        assertEquals(rawSql, def.getSqlScript());
        assertFalse(def.isAutoScriptWrap());
    }

    @Test
    @DisplayName("验证前置参数断言防呆保护")
    void testValidationGuards() {
        // id 不能为空
        assertThrows(IllegalArgumentException.class, () ->
                DynamicSqlDefinition.builder().id("").sqlScript("SELECT 1").build());

        // sqlScript 不能为空
        assertThrows(IllegalArgumentException.class, () ->
                DynamicSqlDefinition.builder().id("test.sql").sqlScript("  ").build());

        // commandType 默认推导为 SELECT
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("test.sql")
                .sqlScript("SELECT 1")
                .build();
        assertEquals(SqlCommandType.SELECT, def.getCommandType());
        assertEquals(Map.class, def.getResultType(), "未指定 resultType 时默认推导为 Map.class");
    }

    @Test
    @DisplayName("验证 projections 投影字典挂载与不可变防御")
    void testProjections() {
        Map<String, String> map = Map.of("userName", "u.user_name", "deptName", "d.dept_name");
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("test.projections")
                .sqlScript("SELECT u.user_name, d.dept_name FROM u JOIN d")
                .projections(map)
                .build();

        assertNotNull(def.getProjections());
        assertEquals("u.user_name", def.getProjections().get("userName"));

        // 验证 withProjections 衍生新实例
        DynamicSqlDefinition updated = def.withProjections(Map.of("userName", "custom_name"));
        assertEquals("custom_name", updated.getProjections().get("userName"));
        assertEquals(def.getId(), updated.getId());

        // 验证不可变性
        assertThrows(UnsupportedOperationException.class, () -> def.getProjections().put("k", "v"));
    }
}
