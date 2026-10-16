package io.github.atengk.boot.mybatisplus.permission;

import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengDataPermissionInnerInterceptor 增强拦截器单元测试
 * 验证声明式 @DataScope 解析、超管与编程式闭包短路放行、表别名防御过滤以及分页 count 语句映射
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("AtengDataPermissionInnerInterceptor 增强数据权限拦截器单元测试")
class AtengDataPermissionInnerInterceptorTest {

    interface NormalMapper {
        void queryAll();
    }

    interface MethodScopeMapper {
        @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "user_id")
        void queryWithScope();

        @DataScope(ignore = true)
        void queryWithExplicitIgnore();

        void queryWithoutScope();
    }

    @DataScope(deptColumn = "department_id")
    interface ClassLevelScopeMapper {
        void queryDefault();

        @DataScope(ignore = true)
        void queryExempt();
    }

    private AtengDataPermissionInnerInterceptor interceptor;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        DataPermissionContextHolder.clear();
        configuration = new Configuration();
        interceptor = new AtengDataPermissionInnerInterceptor((table, where, mappedStatementId, dataScope) -> {
            // 构造简单的 AST 条件：table.alias.column = 'TEST'
            String prefix = dataScope.alias().isBlank() ? "" : dataScope.alias() + ".";
            EqualsTo equalsTo = new EqualsTo();
            equalsTo.setLeftExpression(new Column(prefix + dataScope.deptColumn()));
            equalsTo.setRightExpression(new StringValue("100"));
            return equalsTo;
        });
    }

    @AfterEach
    void tearDown() {
        DataPermissionContextHolder.clear();
    }

    private MappedStatement createStatement(String id) {
        return new MappedStatement.Builder(configuration, id, (parameterObject) -> null, null).build();
    }

    @Test
    @DisplayName("1. 普通无注解方法：shouldIgnore 返回 true，秒级短路跳过 AST 语法树解析")
    void shouldIgnoreNormalMethodWithoutAnnotation() {
        MappedStatement ms = createStatement(NormalMapper.class.getName() + ".queryAll");
        assertThat(interceptor.shouldIgnore(ms)).isTrue();
    }

    @Test
    @DisplayName("2. 方法级 @DataScope 注解：shouldIgnore 返回 false，执行正常数据权限过滤")
    void shouldNotIgnoreMethodWithScope() {
        MappedStatement ms = createStatement(MethodScopeMapper.class.getName() + ".queryWithScope");
        assertThat(interceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("3. 显式 ignore = true：shouldIgnore 返回 true，跳过数据权限过滤")
    void shouldIgnoreMethodWithExplicitIgnore() {
        MappedStatement ms = createStatement(MethodScopeMapper.class.getName() + ".queryWithExplicitIgnore");
        assertThat(interceptor.shouldIgnore(ms)).isTrue();
    }

    @Test
    @DisplayName("4. 类级 @DataScope 注解继承与方法豁免：类上注解默认生效，子方法 ignore 独立生效")
    void shouldHandleClassLevelAnnotationAndMethodOverride() {
        MappedStatement msDefault = createStatement(ClassLevelScopeMapper.class.getName() + ".queryDefault");
        MappedStatement msExempt = createStatement(ClassLevelScopeMapper.class.getName() + ".queryExempt");

        assertThat(interceptor.shouldIgnore(msDefault)).isFalse();
        assertThat(interceptor.shouldIgnore(msExempt)).isTrue();
    }

    @Test
    @DisplayName("5. 编程式闭包 runWithoutPermission：闭包执行期间统一短路放行")
    void shouldIgnoreInRunWithoutPermissionClosure() {
        MappedStatement ms = createStatement(MethodScopeMapper.class.getName() + ".queryWithScope");
        assertThat(interceptor.shouldIgnore(ms)).isFalse();

        DataPermissionContextHolder.runWithoutPermission(() -> {
            assertThat(interceptor.shouldIgnore(ms)).isTrue();
        });

        assertThat(interceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("6. 超级管理员 runAsSuperAdmin：超管身份下统一短路放行")
    void shouldIgnoreInSuperAdminContext() {
        MappedStatement ms = createStatement(MethodScopeMapper.class.getName() + ".queryWithScope");
        assertThat(interceptor.shouldIgnore(ms)).isFalse();

        DataPermissionContextHolder.runAsSuperAdmin(() -> {
            assertThat(interceptor.shouldIgnore(ms)).isTrue();
        });

        assertThat(interceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("7. 分页 _mpCount 语句识别：正确映射回原方法的 @DataScope 注解")
    void shouldResolveMpCountStatementCorrectly() {
        MappedStatement msCount = createStatement(MethodScopeMapper.class.getName() + ".queryWithScope_mpCount");
        assertThat(interceptor.shouldIgnore(msCount)).isFalse();
    }

    @Test
    @DisplayName("8. 表别名防御过滤：buildTableExpression 仅对匹配 alias 的表生成表达式")
    void shouldFilterByTableAliasInBuildTableExpression() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";

        // 匹配目标别名 "u" 的表
        Table matchingTable = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));
        Expression exprMatching = interceptor.buildTableExpression(matchingTable, null, msId);
        assertThat(exprMatching).isNotNull();
        assertThat(exprMatching.toString()).isEqualTo("u.dept_id = '100'");

        // 不匹配目标别名的表 "orders o"
        Table otherTable = new Table("orders").withAlias(new net.sf.jsqlparser.expression.Alias("o"));
        Expression exprOther = interceptor.buildTableExpression(otherTable, null, msId);
        assertThat(exprOther).isNull();
    }

    @Test
    @DisplayName("9. 软删除与逻辑删除边界：存在现有 where 条件 (deleted = 0) 时正确生成 AST 权限表达式")
    void shouldGenerateExpressionWhenWhereContainsLogicDelete() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";
        Table matchingTable = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));

        // 模拟已存在逻辑删除条件 u.deleted = 0
        EqualsTo existingWhere = new EqualsTo();
        existingWhere.setLeftExpression(new Column("u.deleted"));
        existingWhere.setRightExpression(new LongValue(0));

        Expression expr = interceptor.buildTableExpression(matchingTable, existingWhere, msId);
        assertThat(expr).isNotNull();
        assertThat(expr.toString()).isEqualTo("u.dept_id = '100'");
    }
}
