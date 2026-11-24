package io.github.atengk.boot.mybatisplus.permission;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.enums.DataPermissionFailureStrategy;
import io.github.atengk.boot.mybatisplus.tenant.AtengTenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("10. OrExpression 自动加括号防卫：顶层裸 OrExpression 自动包裹括号，已包裹不重复嵌套双括号")
    void shouldWrapTopLevelNakedOrExpressionWithoutDoubleWrapping() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";
        Table matchingTable = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));

        // 10.1 返回裸 OrExpression
        AtengDataPermissionInnerInterceptor rawOrInterceptor = new AtengDataPermissionInnerInterceptor(
                (table, where, mappedStatementId, dataScope) -> new OrExpression(
                        new EqualsTo(new Column("u." + dataScope.deptColumn()), new LongValue(10L)),
                        new EqualsTo(new Column("u." + dataScope.userColumn()), new LongValue(1001L))
                )
        );
        Expression wrapped = rawOrInterceptor.buildTableExpression(matchingTable, null, msId);
        assertThat(wrapped).isInstanceOf(ParenthesedExpressionList.class);
        assertThat(wrapped.toString()).isEqualTo("(u.dept_id = 10 OR u.user_id = 1001)");

        // 10.2 业务 SPI 已手动包裹 ParenthesedExpressionList 时，不再生成 ((...)) 双重括号
        AtengDataPermissionInnerInterceptor preWrappedInterceptor = new AtengDataPermissionInnerInterceptor(
                (table, where, mappedStatementId, dataScope) -> new ParenthesedExpressionList<>(new OrExpression(
                        new EqualsTo(new Column("u." + dataScope.deptColumn()), new LongValue(10L)),
                        new EqualsTo(new Column("u." + dataScope.userColumn()), new LongValue(1001L))
                ))
        );
        Expression alreadyWrapped = preWrappedInterceptor.buildTableExpression(matchingTable, null, msId);
        assertThat(alreadyWrapped.toString()).isEqualTo("(u.dept_id = 10 OR u.user_id = 1001)");
    }

    @Test
    @DisplayName("11. 组合 AST 优先级递归防卫：AndExpression 与 NotExpression 内嵌裸 OrExpression 自动补齐括号且不篡改共享实例")
    void shouldRecursivelyProtectOrExpressionInsideAndAndNotWithoutMutatingSharedAst() {
        String msId = ClassLevelScopeMapper.class.getName() + ".queryDefault";
        Table table = new Table("sys_user");

        // 构造共享的未加括号嵌套表达式：status = 1 AND (dept_id = 10 OR user_id = 1001)
        OrExpression sharedInnerOr = new OrExpression(
                new EqualsTo(new Column("dept_id"), new LongValue(10L)),
                new EqualsTo(new Column("user_id"), new LongValue(1001L))
        );
        AndExpression sharedAnd = new AndExpression(
                new EqualsTo(new Column("status"), new LongValue(1L)),
                sharedInnerOr
        );

        AtengDataPermissionInnerInterceptor andOrInterceptor = new AtengDataPermissionInnerInterceptor(
                (t, w, id, ds) -> sharedAnd
        );
        Expression protectedAnd = andOrInterceptor.buildTableExpression(table, null, msId);
        assertThat(protectedAnd).isNotSameAs(sharedAnd);
        assertThat(protectedAnd.toString()).isEqualTo("status = 1 AND (dept_id = 10 OR user_id = 1001)");
        // 原共享实例未被就地修改
        assertThat(sharedAnd.toString()).isEqualTo("status = 1 AND dept_id = 10 OR user_id = 1001");

        // NotExpression 内嵌裸 OrExpression 同样加括号保护
        AtengDataPermissionInnerInterceptor notOrInterceptor = new AtengDataPermissionInnerInterceptor(
                (t, w, id, ds) -> new NotExpression(sharedInnerOr)
        );
        Expression protectedNot = notOrInterceptor.buildTableExpression(table, null, msId);
        assertThat(protectedNot.toString()).isEqualTo("NOT (dept_id = 10 OR user_id = 1001)");
    }

    @Test
    @DisplayName("12. 全链 SQL 改写端到端 AST 断言：多租户 + 逻辑删除叠加裸 OrExpression 在 SELECT/UPDATE/DELETE 均安全隔离")
    void shouldProtectTenantAndLogicDeletePrecedenceInRewrittenSqlStatements() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";
        AtengTenantLineInnerInterceptor tenantInterceptor = new AtengTenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(1L);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }
        });
        AtengDataPermissionInnerInterceptor dpInterceptor = new AtengDataPermissionInnerInterceptor(
                (table, where, mappedStatementId, dataScope) -> new OrExpression(
                        new EqualsTo(new Column("u." + dataScope.deptColumn()), new LongValue(10L)),
                        new EqualsTo(new Column("u." + dataScope.userColumn()), new LongValue(1001L))
                )
        );

        // 12.1 SELECT 语句：多租户 + 软删 + 裸 OrExpression 数据权限
        String selectSql = "SELECT u.id, u.name FROM sys_user u WHERE u.deleted = 0";
        String afterTenantSelect = tenantInterceptor.parserSingle(selectSql, msId);
        String finalSelectSql = dpInterceptor.parserSingle(afterTenantSelect, msId);
        assertThat(finalSelectSql).isEqualTo(
                "SELECT u.id, u.name FROM sys_user u WHERE u.deleted = 0 AND u.tenant_id = 1 AND (u.dept_id = 10 OR u.user_id = 1001)"
        );

        // 12.2 UPDATE 语句
        String updateSql = "UPDATE sys_user u SET u.name = 'updated' WHERE u.deleted = 0";
        String afterTenantUpdate = tenantInterceptor.parserMulti(updateSql, msId);
        String finalUpdateSql = dpInterceptor.parserMulti(afterTenantUpdate, msId);
        assertThat(finalUpdateSql).isEqualTo(
                "UPDATE sys_user u SET u.name = 'updated' WHERE u.deleted = 0 AND u.tenant_id = 1 AND (u.dept_id = 10 OR u.user_id = 1001)"
        );

        // 12.3 DELETE 语句
        String deleteSql = "DELETE FROM sys_user u WHERE u.deleted = 0";
        String afterTenantDelete = tenantInterceptor.parserMulti(deleteSql, msId);
        String finalDeleteSql = dpInterceptor.parserMulti(afterTenantDelete, msId);
        assertThat(finalDeleteSql).isEqualTo(
                "DELETE FROM sys_user u WHERE u.deleted = 0 AND u.tenant_id = 1 AND (u.dept_id = 10 OR u.user_id = 1001)"
        );
    }

    @Test
    @DisplayName("13. 可配失败策略验证：默认及显式 DENY_ALL 返回 1 = 0；THROW 保留 cause 链；FAIL_OPEN 降级放行")
    void shouldApplyConfiguredFailureStrategyOnUnexpectedHandlerException() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";
        Table matchingTable = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));
        IllegalStateException rootCause = new IllegalStateException("RBAC 权限中心连接超时");
        DataPermissionHandler brokenHandler = (table, where, mappedStatementId, dataScope) -> {
            throw rootCause;
        };

        // 13.1 1 参构造默认 DENY_ALL -> 1 = 0
        AtengDataPermissionInnerInterceptor defaultDenyInterceptor = new AtengDataPermissionInnerInterceptor(brokenHandler);
        assertThat(defaultDenyInterceptor.getFailureStrategy()).isEqualTo(DataPermissionFailureStrategy.DENY_ALL);
        Expression denyExpr = defaultDenyInterceptor.buildTableExpression(matchingTable, null, msId);
        assertThat(denyExpr).isNotNull();
        assertThat(denyExpr.toString()).isEqualTo("1 = 0");

        // 验证多租户叠加下 DENY_ALL 最终 SQL 含 AND 1 = 0
        String rewrittenSql = defaultDenyInterceptor.parserSingle("SELECT u.id FROM sys_user u WHERE u.deleted = 0", msId);
        assertThat(rewrittenSql).isEqualTo("SELECT u.id FROM sys_user u WHERE u.deleted = 0 AND 1 = 0");

        // 13.2 THROW 策略 -> 抛出 BusinessException 并绑定 IResultCode 且完整保留原始 cause 链
        AtengDataPermissionInnerInterceptor throwInterceptor = new AtengDataPermissionInnerInterceptor(
                brokenHandler, DataPermissionFailureStrategy.THROW);
        assertThatThrownBy(() -> throwInterceptor.buildTableExpression(matchingTable, null, msId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getResultCode())
                        .isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR))
                .hasMessageContaining("数据权限 AST 条件生成失败")
                .hasCause(rootCause);

        // 13.3 FAIL_OPEN 策略 -> 记录错误日志并返回 null（放行）
        AtengDataPermissionInnerInterceptor failOpenInterceptor = new AtengDataPermissionInnerInterceptor(
                brokenHandler, DataPermissionFailureStrategy.FAIL_OPEN);
        Expression failOpenExpr = failOpenInterceptor.buildTableExpression(matchingTable, null, msId);
        assertThat(failOpenExpr).isNull();
    }

    @Test
    @DisplayName("14. BusinessException 原样透传防卫：SPI 主动抛出的显式鉴权/业务异常不被 DENY_ALL 或 FAIL_OPEN 吞咽")
    void shouldRethrowExplicitBusinessExceptionWithoutDowngrade() {
        String msId = MethodScopeMapper.class.getName() + ".queryWithScope";
        Table matchingTable = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));
        BusinessException explicitAuthError = new BusinessException(CommonResultCode.FORBIDDEN.getCode(), "当前账号未分配组织数据域");
        DataPermissionHandler authRejectHandler = (table, where, mappedStatementId, dataScope) -> {
            throw explicitAuthError;
        };

        AtengDataPermissionInnerInterceptor denyInterceptor = new AtengDataPermissionInnerInterceptor(
                authRejectHandler, DataPermissionFailureStrategy.DENY_ALL);
        assertThatThrownBy(() -> denyInterceptor.buildTableExpression(matchingTable, null, msId))
                .isSameAs(explicitAuthError);

        AtengDataPermissionInnerInterceptor failOpenInterceptor = new AtengDataPermissionInnerInterceptor(
                authRejectHandler, DataPermissionFailureStrategy.FAIL_OPEN);
        assertThatThrownBy(() -> failOpenInterceptor.buildTableExpression(matchingTable, null, msId))
                .isSameAs(explicitAuthError);
    }
}
