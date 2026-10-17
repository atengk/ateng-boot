package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import io.github.atengk.boot.sample.mapper.SysDocumentMapper;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基于 JSqlParser AST 的细粒度数据权限引擎端到端集成测试
 * 验证 @DataScope 注解、本人数据权限、部门数据权限、多表关联 JOIN 别名改写、分页 count 保护、超管放行与编程式闭包全生命周期
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.data-permission.enabled=true"
})
@Import(SampleDataPermissionIntegrationTest.TestDataPermissionConfig.class)
@DisplayName("细粒度数据权限引擎 (@DataScope) 端到端集成测试")
class SampleDataPermissionIntegrationTest {

    /**
     * 模拟测试安全上下文（模拟登录用户权限范围）
     */
    static class SecurityContext {
        static final ThreadLocal<String> SCOPE_MODE = new ThreadLocal<>(); // "SELF", "DEPT", "ALL"
        static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
        static final ThreadLocal<List<Long>> CURRENT_DEPT_IDS = new ThreadLocal<>();

        static void clear() {
            SCOPE_MODE.remove();
            CURRENT_USER_ID.remove();
            CURRENT_DEPT_IDS.remove();
        }
    }

    @TestConfiguration
    static class TestDataPermissionConfig {

        @Bean
        @Primary
        public DataPermissionHandler testDataPermissionHandler() {
            return (table, where, mappedStatementId, dataScope) -> {
                String mode = SecurityContext.SCOPE_MODE.get();
                if (mode == null || "ALL".equals(mode)) {
                    return null;
                }

                String prefix = dataScope.alias().isBlank() ? "" : dataScope.alias() + ".";

                if ("SELF".equals(mode)) {
                    // user_id = ?
                    Long userId = SecurityContext.CURRENT_USER_ID.get();
                    if (userId == null) {
                        return null;
                    }
                    EqualsTo equalsTo = new EqualsTo();
                    equalsTo.setLeftExpression(new Column(prefix + dataScope.userColumn()));
                    equalsTo.setRightExpression(new LongValue(userId));
                    return equalsTo;
                }

                if ("DEPT".equals(mode)) {
                    // dept_id IN (...)
                    List<Long> deptIds = SecurityContext.CURRENT_DEPT_IDS.get();
                    if (deptIds == null || deptIds.isEmpty()) {
                        return null;
                    }
                    ParenthesedExpressionList<LongValue> list = new ParenthesedExpressionList<>();
                    for (Long id : deptIds) {
                        list.add(new LongValue(id));
                    }
                    InExpression inExpression = new InExpression();
                    inExpression.setLeftExpression(new Column(prefix + dataScope.deptColumn()));
                    inExpression.setRightExpression(list);
                    return inExpression;
                }

                return null;
            };
        }
    }

    @Autowired
    private SysDocumentMapper documentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DataPermissionContextHolder.clear();
        SecurityContext.clear();

        // 清理测试表
        jdbcTemplate.execute("DELETE FROM sys_document");
        jdbcTemplate.execute("DELETE FROM sys_user");

        // 插入用户数据
        jdbcTemplate.update("INSERT INTO sys_user (id, name) VALUES (1001, '张三')");
        jdbcTemplate.update("INSERT INTO sys_user (id, name) VALUES (1002, '李四')");
        jdbcTemplate.update("INSERT INTO sys_user (id, name) VALUES (2001, '王五')");
        jdbcTemplate.update("INSERT INTO sys_user (id, name) VALUES (3001, '赵六')");

        // 插入 4 条测试文档
        insertDocument(1L, "人事制度规范", 10L, 1001L);
        insertDocument(2L, "招聘执行流程", 10L, 1002L);
        insertDocument(3L, "研发编码规约", 20L, 2001L);
        insertDocument(4L, "季度财务报表", 30L, 3001L);
    }

    private void insertDocument(Long id, String title, Long deptId, Long userId) {
        SysDocumentEntity doc = new SysDocumentEntity();
        doc.setId(id);
        doc.setTitle(title);
        doc.setDeptId(deptId);
        doc.setUserId(userId);
        doc.setCreateTime(LocalDateTime.now());
        documentMapper.insert(doc);
    }

    @AfterEach
    void tearDown() {
        DataPermissionContextHolder.clear();
        SecurityContext.clear();
        jdbcTemplate.execute("DELETE FROM sys_document");
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    @Test
    @DisplayName("1. 本人数据权限过滤：自动追加 user_id = 1001，仅查出当前用户自己的文档")
    void shouldFilterBySelfDataScope() {
        SecurityContext.SCOPE_MODE.set("SELF");
        SecurityContext.CURRENT_USER_ID.set(1001L);

        List<SysDocumentEntity> list = documentMapper.selectListWithScope();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTitle()).isEqualTo("人事制度规范");
        assertThat(list.get(0).getUserId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("2. 部门数据权限过滤：自动追加 dept_id IN (10)，查出本部门下全部文档")
    void shouldFilterByDeptDataScope() {
        SecurityContext.SCOPE_MODE.set("DEPT");
        SecurityContext.CURRENT_DEPT_IDS.set(List.of(10L));

        List<SysDocumentEntity> list = documentMapper.selectListWithScope();

        assertThat(list).hasSize(2);
        assertThat(list).extracting(SysDocumentEntity::getTitle)
                .containsExactlyInAnyOrder("人事制度规范", "招聘执行流程");
    }

    @Test
    @DisplayName("3. 跨部门多选权限过滤：自动追加 dept_id IN (10, 20)，查出多部门聚合文档")
    void shouldFilterByMultiDeptDataScope() {
        SecurityContext.SCOPE_MODE.set("DEPT");
        SecurityContext.CURRENT_DEPT_IDS.set(List.of(10L, 20L));

        List<SysDocumentEntity> list = documentMapper.selectListWithScope();

        assertThat(list).hasSize(3);
        assertThat(list).extracting(SysDocumentEntity::getTitle)
                .containsExactlyInAnyOrder("人事制度规范", "招聘执行流程", "研发编码规约");
    }

    @Test
    @DisplayName("4. 多表关联 JOIN 查询：指定 alias = 'd'，精准为文档别名追加过滤条件")
    void shouldFilterJoinQueryWithTableAlias() {
        SecurityContext.SCOPE_MODE.set("DEPT");
        SecurityContext.CURRENT_DEPT_IDS.set(List.of(10L));

        List<Map<String, Object>> list = documentMapper.selectJoinWithScope();

        assertThat(list).hasSize(2);
        assertThat(list).extracting(map -> map.get("USER_NAME"))
                .containsExactlyInAnyOrder("张三", "李四");
    }

    @Test
    @DisplayName("5. 分页查询受权限过滤保护：count 语句与数据列表同步应用 @DataScope 过滤")
    void shouldProtectPaginationCountQuery() {
        SecurityContext.SCOPE_MODE.set("DEPT");
        SecurityContext.CURRENT_DEPT_IDS.set(List.of(10L));

        Page<SysDocumentEntity> page = documentMapper.selectPageWithScope(new Page<>(1, 10));

        // 验证分页 count 统计到的总数严格为 2，而非全表总数 4
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("6. 超级管理员身份秒级放行：runAsSuperAdmin 闭包执行期间跳过改写，全量查询")
    void shouldBypassDataScopeForSuperAdmin() {
        SecurityContext.SCOPE_MODE.set("SELF");
        SecurityContext.CURRENT_USER_ID.set(1001L);

        // 默认受限查询出 1 条
        assertThat(documentMapper.selectListWithScope()).hasSize(1);

        // 切换为超级管理员身份执行，秒级放行全量 4 条
        DataPermissionContextHolder.runAsSuperAdmin(() -> {
            List<SysDocumentEntity> allDocs = documentMapper.selectListWithScope();
            assertThat(allDocs).hasSize(4);
        });

        // 退出超管上下文后自动恢复权限受限
        assertThat(documentMapper.selectListWithScope()).hasSize(1);
    }

    @Test
    @DisplayName("7. 编程式忽略闭包放行：runWithoutPermission 闭包内跳过改写，全量查询")
    void shouldBypassDataScopeInRunWithoutPermission() {
        SecurityContext.SCOPE_MODE.set("SELF");
        SecurityContext.CURRENT_USER_ID.set(1001L);

        DataPermissionContextHolder.runWithoutPermission(() -> {
            List<SysDocumentEntity> allDocs = documentMapper.selectListWithScope();
            assertThat(allDocs).hasSize(4);
        });

        // 退出闭包后恢复权限过滤
        assertThat(documentMapper.selectListWithScope()).hasSize(1);
    }

    @Test
    @DisplayName("8. 未标注 @DataScope 普通方法：拦截器秒级短路放行，零语法树解析开销，查询全量")
    void shouldBypassNormalMethodWithoutAnnotation() {
        SecurityContext.SCOPE_MODE.set("SELF");
        SecurityContext.CURRENT_USER_ID.set(1001L);

        List<SysDocumentEntity> list = documentMapper.selectNormal();
        assertThat(list).hasSize(4);
    }

    @Test
    @DisplayName("9. 显式豁免 @DataScope(ignore = true)：显式声明跳过权限过滤，查询全量")
    void shouldBypassExemptMethodWithExplicitIgnore() {
        SecurityContext.SCOPE_MODE.set("SELF");
        SecurityContext.CURRENT_USER_ID.set(1001L);

        List<SysDocumentEntity> list = documentMapper.selectExempt();
        assertThat(list).hasSize(4);
    }
}
