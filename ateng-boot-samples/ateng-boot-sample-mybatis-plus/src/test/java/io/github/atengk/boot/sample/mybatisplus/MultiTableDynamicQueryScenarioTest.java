package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryParser;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryCondition;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.sample.mybatisplus.model.UserDeptDetailVO;
import io.github.atengk.boot.sample.mybatisplus.service.MultiTableDynamicQueryScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多表联查动态 AST 条件推导与别名自愈端到端集成测试
 * 验证：
 * 1. 结构化动态请求 deptName:like=研发&age:gte=20 自动由 DynamicSqlProjectionResolver 将 VO 属性映射为物理列 (deptName -> d.dept_name)
 * 2. 针对联表 Mapper 方法引用 (UserMapper::selectUserDeptPage) 执行动态分页查询并正确返回 VO 投影列表与分页元数据
 * 3. 依托 DynamicQueryTemplate 执行基于 StatementId 的多表联查与投影自愈
 * 4. 验证多表存在性判定 existsDynamic 单页 Page(1, 1, false) 物理短路判定机制
 * 5. 防御性空安全：无匹配记录保底返回空分页，严禁返回 null
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
@DisplayName("多表联查动态 AST 条件推导与别名自愈场景集成自验 (MultiTableDynamicQueryScenario)")
class MultiTableDynamicQueryScenarioTest {

    @Autowired
    private MultiTableDynamicQueryScenarioService scenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanDb();

        // 插入测试部门数据
        jdbcTemplate.execute("INSERT INTO sys_dept (id, dept_name, status, version, deleted) VALUES " +
                "(101, '研发一部', 'ACTIVE', 0, 0), " +
                "(102, '研发二部', 'ACTIVE', 0, 0), " +
                "(103, '运营中心', 'INACTIVE', 0, 0)");

        // 插入测试用户数据（包含关联 dept_id）
        jdbcTemplate.execute("INSERT INTO sys_user (id, name, age, email, phone, dept_id, version, deleted) VALUES " +
                "(1001, '张三', 25, 'zhangsan@example.com', '13800000001', 101, 0, 0), " +
                "(1002, '李四', 28, 'lisi@example.com', '13800000002', 101, 0, 0), " +
                "(1003, '王五', 32, 'wangwu@example.com', '13800000003', 102, 0, 0), " +
                "(1004, '赵六', 19, 'zhaoliu@example.com', '13800000004', 101, 0, 0), " +
                "(1005, '孙七', 40, 'sunqi@example.com', '13800000005', 103, 0, 0)");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        cleanDb();
    }

    private void cleanDb() {
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("DELETE FROM sys_dept");
    }

    @Test
    @DisplayName("1. 验证结构化动态请求 deptName:like=研发&age:gte=20 自动完成 AST 别名自愈与多表分页")
    void shouldAutoHealAliasAndPageWithUrlQuery() {
        // 模拟前端传入的带冒号操作符动态 URL Query 参数
        String queryString = "current=1&size=10&deptName:like=研发&age:gte=20&sort=userId:asc";
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);

        // 依托方法引用进行分页查询
        PageResult<UserDeptDetailVO> result = scenarioService.pageUserDeptByMethodRef(request);

        assertThat(result).isNotNull();
        // 研发部门下年龄 >= 20 的用户：张三 (25), 李四 (28), 王五 (32)。赵六 (19) 与孙七 (运营中心) 被过滤
        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getRecords()).hasSize(3);

        UserDeptDetailVO u1 = result.getRecords().get(0);
        assertThat(u1.getUserId()).isEqualTo(1001L);
        assertThat(u1.getUserName()).isEqualTo("张三");
        assertThat(u1.getDeptName()).isEqualTo("研发一部");
        assertThat(u1.getDeptStatus()).isEqualTo("ACTIVE");

        UserDeptDetailVO u2 = result.getRecords().get(1);
        assertThat(u2.getUserId()).isEqualTo(1002L);
        assertThat(u2.getUserName()).isEqualTo("李四");
        assertThat(u2.getDeptName()).isEqualTo("研发一部");

        UserDeptDetailVO u3 = result.getRecords().get(2);
        assertThat(u3.getUserId()).isEqualTo(1003L);
        assertThat(u3.getUserName()).isEqualTo("王五");
        assertThat(u3.getDeptName()).isEqualTo("研发二部");
    }

    @Test
    @DisplayName("2. 验证多表联查 AST 投影字典解析与字段映射准确性")
    void shouldResolveProjectionsAccurately() {
        Map<String, String> projections = scenarioService.getDynamicQueryTemplate()
                .resolveProjections("selectUserDeptPage");

        assertThat(projections).isNotEmpty();
        // 验证多表别名正确解析
        assertThat(projections).containsEntry("deptName", "d.dept_name");
        assertThat(projections).containsEntry("dept_name", "d.dept_name");
        assertThat(projections).containsEntry("userId", "u.id");
        assertThat(projections).containsEntry("userName", "u.name");
        assertThat(projections).containsEntry("age", "u.age");
        assertThat(projections).containsEntry("deptStatus", "d.status");
    }

    @Test
    @DisplayName("3. 验证基于 DynamicQueryTemplate 执行基于 StatementId 的多表流式物理分页")
    void shouldPageUserDeptByTemplate() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(2L);
        request.addCondition(QueryCondition.eq("deptStatus", "ACTIVE"));
        request.addCondition(QueryCondition.ge("age", 25));

        PageResult<UserDeptDetailVO> pageResult = scenarioService.pageUserDeptByTemplate(request);

        assertThat(pageResult).isNotNull();
        assertThat(pageResult.getTotal()).isEqualTo(3L);
        assertThat(pageResult.getPages()).isEqualTo(2L);
        assertThat(pageResult.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("4. 验证多表存在性判定 existsDynamic 单页 Page(1, 1, false) 物理短路判定机制")
    void shouldSupportShortCircuitExistsEvaluation() {
        // 1. 命中存在记录 (研发二部且年龄 >= 30: 王五)
        DynamicQueryRequest hitRequest = new DynamicQueryRequest();
        hitRequest.addCondition(QueryCondition.eq("deptName", "研发二部"));
        hitRequest.addCondition(QueryCondition.ge("age", 30));

        boolean existsByMethodRef = scenarioService.existsUserDeptByMethodRef(hitRequest);
        assertThat(existsByMethodRef).isTrue();

        boolean existsByTemplate = scenarioService.existsUserDeptByTemplate(hitRequest);
        assertThat(existsByTemplate).isTrue();

        // 2. 未命中记录 (不存在市场部的用户)
        DynamicQueryRequest missRequest = new DynamicQueryRequest();
        missRequest.addCondition(QueryCondition.eq("deptName", "市场营销部"));

        boolean missByMethodRef = scenarioService.existsUserDeptByMethodRef(missRequest);
        assertThat(missByMethodRef).isFalse();

        boolean missByTemplate = scenarioService.existsUserDeptByTemplate(missRequest);
        assertThat(missByTemplate).isFalse();
    }

    @Test
    @DisplayName("5. 验证复杂 OR 分组条件下的多表动态查询")
    void shouldSupportOrGroupingConditions() {
        // (deptName = '运营中心' OR userName = '王五')
        String queryString = "current=1&size=10&or_1:deptName=运营中心&or_1:userName=王五";
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);

        PageResult<UserDeptDetailVO> result = scenarioService.pageUserDeptByMethodRef(request);

        assertThat(result).isNotNull();
        // 王五 (研发二部) + 孙七 (运营中心)
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).extracting(UserDeptDetailVO::getUserName)
                .containsExactlyInAnyOrder("王五", "孙七");
    }

    @Test
    @DisplayName("6. 验证防御性与空安全契约：null 入参保底返回空分页，严禁 NPE")
    void shouldHandleNullAndEmptyRequestsDefensively() {
        PageResult<UserDeptDetailVO> emptyResult = scenarioService.pageUserDeptByMethodRef(null);
        assertThat(emptyResult).isNotNull();
        assertThat(emptyResult.getTotal()).isEqualTo(0L);
        assertThat(emptyResult.getRecords()).isNotNull().isEmpty();

        boolean existsOnNull = scenarioService.existsUserDeptByMethodRef(null);
        assertThat(existsOnNull).isFalse();
    }
}
