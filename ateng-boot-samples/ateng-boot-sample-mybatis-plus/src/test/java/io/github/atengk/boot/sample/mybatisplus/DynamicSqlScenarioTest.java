package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicSqlScenarioService;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部动态 SQL 脚本热注册与免 Mapper 执行场景集成测试
 * 验证：
 * 1. 启动期自动从资源路径扫描并注册 StatementId
 * 2. 免 Mapper 接口调用 DynamicSqlExecutor 执行动态查询并完成 VO 强类型投影
 * 3. 深度融合 ADR-0010 分页契约执行流式分页与元数据回写
 * 4. 运行时编程式动态注入全新动态 SQL 并即时执行
 * 5. 运行时热替换已有 StatementId 并即时生效
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("外部动态 SQL 脚本热注册与免 Mapper 执行场景集成自验 (DynamicSqlScenario)")
class DynamicSqlScenarioTest {

    @Autowired
    private DynamicSqlScenarioService dynamicSqlScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("INSERT INTO sys_user (id, name, age, email, phone, dept_id, version, deleted) VALUES " +
                "(1001, '张三', 25, 'zhangsan@example.com', '13800000001', 1, 0, 0), " +
                "(1002, '李四', 30, 'lisi@example.com', '13800000002', 1, 0, 0), " +
                "(1003, '王五', 35, 'wangwu@example.com', '13800000003', 2, 0, 0), " +
                "(1004, '张伟', 40, 'zhangwei@example.com', '13800000004', 2, 0, 0)");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        dynamicSqlScenarioService.removeCustomSql("custom.runtimeUserQuery");
        dynamicSqlScenarioService.removeCustomSql("custom.hotSwapDemo");
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    @Test
    @DisplayName("1. 验证应用启动期自动扫描并注册外部 XML 脚本中的 Statement")
    void shouldLoadAndRegisterStatementsOnStartup() {
        assertThat(dynamicSqlScenarioService.getLoader()).isNotNull();
        assertThat(dynamicSqlScenarioService.getExecutor()).isNotNull();
        assertThat(dynamicSqlScenarioService.getRegistry()).isNotNull();

        assertThat(dynamicSqlScenarioService.getRegistry().hasStatement("user.findUsersDynamic")).isTrue();
        assertThat(dynamicSqlScenarioService.getRegistry().hasStatement("user.countUsersDynamic")).isTrue();

        DynamicSqlDefinition def = dynamicSqlScenarioService.getRegistry().getDefinition("user.findUsersDynamic");
        assertThat(def).isNotNull();
        assertThat(def.getDescription()).isEqualTo("动态条件查询系统用户列表");
    }

    @Test
    @DisplayName("2. 验证免 Mapper 接口执行多条件动态查询并强类型映射为 VO")
    void shouldExecuteSelectListWithDynamicConditions() {
        // 1. 条件匹配：模糊查询名称 "张"，最小年龄 25
        List<UserDynamicVO> users = dynamicSqlScenarioService.findUsersDynamic("张", 25, null);
        assertThat(users).hasSize(2);
        assertThat(users).extracting(UserDynamicVO::getName).containsExactly("张三", "张伟");
        assertThat(users.get(0).getId()).isEqualTo(1001L);
        assertThat(users.get(0).getAge()).isEqualTo(25);
        assertThat(users.get(0).getDeptId()).isEqualTo(1L);

        // 2. 部门精确匹配：deptId = 2
        List<UserDynamicVO> deptUsers = dynamicSqlScenarioService.findUsersDynamic(null, null, 2L);
        assertThat(deptUsers).hasSize(2);
        assertThat(deptUsers).extracting(UserDynamicVO::getName).containsExactly("王五", "张伟");

        // 3. 空安全保底：无匹配条件保底返回空 List
        List<UserDynamicVO> emptyUsers = dynamicSqlScenarioService.findUsersDynamic("不存在的用户", null, null);
        assertThat(emptyUsers).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("3. 验证融合 ADR-0010 分页契约：自动触发物理分页与元数据回写")
    void shouldExecuteSelectPageWithPaginationMetadata() {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setCurrent(1L);
        pageQuery.setSize(2L);

        PageResult<UserDynamicVO> pageResult = dynamicSqlScenarioService.findUsersDynamicPage(null, null, pageQuery);

        assertThat(pageResult).isNotNull();
        assertThat(pageResult.getTotal()).isEqualTo(4L);
        assertThat(pageResult.getPages()).isEqualTo(2L);
        assertThat(pageResult.getCurrent()).isEqualTo(1L);
        assertThat(pageResult.getSize()).isEqualTo(2L);
        assertThat(pageResult.getRecords()).hasSize(2);
        assertThat(pageResult.getRecords().get(0).getName()).isEqualTo("张三");
        assertThat(pageResult.getRecords().get(1).getName()).isEqualTo("李四");

        // 第二页
        PageQuery page2 = new PageQuery();
        page2.setCurrent(2L);
        page2.setSize(2L);
        PageResult<UserDynamicVO> page2Result = dynamicSqlScenarioService.findUsersDynamicPage(null, null, page2);
        assertThat(page2Result.getRecords()).hasSize(2);
        assertThat(page2Result.getRecords().get(0).getName()).isEqualTo("王五");
        assertThat(page2Result.getRecords().get(1).getName()).isEqualTo("张伟");
    }

    @Test
    @DisplayName("4. 验证免 Mapper 接口动态统计数量")
    void shouldExecuteCountUsersDynamic() {
        long countZhang = dynamicSqlScenarioService.countUsersDynamic("张", null, null);
        assertThat(countZhang).isEqualTo(2L);

        long countDept2 = dynamicSqlScenarioService.countUsersDynamic(null, null, 2L);
        assertThat(countDept2).isEqualTo(2L);

        long countOldDept2 = dynamicSqlScenarioService.countUsersDynamic(null, 38, 2L);
        assertThat(countOldDept2).isEqualTo(1L);
    }

    @Test
    @DisplayName("5. 验证运行时编程式注册全新动态 SQL 并即时执行")
    void shouldRegisterAndExecuteCustomSqlAtRuntime() {
        String statementId = "custom.runtimeUserQuery";

        DynamicSqlDefinition customDef = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT id, name, age FROM sys_user <where><if test=\"minAge != null\">AND age &gt;= #{minAge}</if></where> ORDER BY age DESC")
                .commandType(SqlCommandType.SELECT)
                .description("运行时动态注入的年龄降序查询")
                .build();

        boolean registered = dynamicSqlScenarioService.registerCustomSql(customDef);
        assertThat(registered).isTrue();
        assertThat(dynamicSqlScenarioService.getRegistry().hasStatement(statementId)).isTrue();

        List<Map<String, Object>> result = dynamicSqlScenarioService.executeCustomQuery(statementId, Map.of("minAge", 32));
        assertThat(result).hasSize(2);

        // 验证返回字段
        Object firstAge = result.get(0).get("AGE") != null ? result.get(0).get("AGE") : result.get(0).get("age");
        Object secondAge = result.get(1).get("AGE") != null ? result.get(1).get("AGE") : result.get(1).get("age");
        assertThat(((Number) firstAge).intValue()).isEqualTo(40);
        assertThat(((Number) secondAge).intValue()).isEqualTo(35);
    }

    @Test
    @DisplayName("6. 验证运行时热替换已有 StatementId 并即刻生效")
    void shouldHotReplaceStatementAtRuntimeImmediately() {
        String statementId = "custom.hotSwapDemo";

        // 1. 注册版本 1：仅查询 NAME
        DynamicSqlDefinition v1 = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT name FROM sys_user WHERE id = #{id}")
                .commandType(SqlCommandType.SELECT)
                .description("版本 1: 仅查询姓名")
                .build();
        assertThat(dynamicSqlScenarioService.registerCustomSql(v1)).isTrue();

        List<Map<String, Object>> r1 = dynamicSqlScenarioService.executeCustomQuery(statementId, Map.of("id", 1001L));
        assertThat(r1).hasSize(1);
        Map<String, Object> row1 = r1.get(0);
        assertThat(row1.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("name"))).isTrue();
        assertThat(row1.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("age"))).isTrue();

        // 2. 热替换版本 2：扩展查询 NAME 与 AGE
        DynamicSqlDefinition v2 = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT name, age FROM sys_user WHERE id = #{id}")
                .commandType(SqlCommandType.SELECT)
                .description("版本 2: 查询姓名与年龄")
                .build();
        assertThat(dynamicSqlScenarioService.replaceCustomSql(v2)).isTrue();

        List<Map<String, Object>> r2 = dynamicSqlScenarioService.executeCustomQuery(statementId, Map.of("id", 1001L));
        assertThat(r2).hasSize(1);
        Map<String, Object> row2 = r2.get(0);
        assertThat(row2.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("name"))).isTrue();
        assertThat(row2.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("age"))).isTrue();
    }
}
