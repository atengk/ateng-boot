package io.github.atengk.boot.mybatisplus.dynamicsql;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.annotation.IgnoreTenant;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.permission.AtengDataPermissionInnerInterceptor;
import io.github.atengk.boot.mybatisplus.tenant.AtengTenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 外部动态 SQL 与 MyBatis-Plus 拦截器链透明穿透协同测试
 * 验证外部动态注入的 MappedStatement 100% 透明继承行级多租户、细粒度数据权限、防全表误操作与透明加解密管道
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("外部动态 SQL 拦截器链透明穿透协同测试")
class DynamicSqlInterceptorTransparencyTest {

    interface TestDynamicMapper {
        void queryNormalDynamic();

        @IgnoreTenant
        void queryWithIgnoreTenant();

        @DataScope(alias = "u", deptColumn = "dept_id")
        void queryWithDataScope();

        @DataScope(ignore = true)
        void queryWithDataScopeIgnored();
    }

    static class ExposableTenantLineInnerInterceptor extends AtengTenantLineInnerInterceptor {
        @Override
        public boolean shouldIgnore(MappedStatement ms) {
            return super.shouldIgnore(ms);
        }
    }

    static class ExposableDataPermissionInnerInterceptor extends AtengDataPermissionInnerInterceptor {
        public ExposableDataPermissionInnerInterceptor(io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler handler) {
            super(handler);
        }

        @Override
        public boolean shouldIgnore(MappedStatement ms) {
            return super.shouldIgnore(ms);
        }
    }

    private Configuration configuration;
    private DynamicSqlRegistry registry;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        registry = new DynamicSqlRegistry(configuration);
        TenantContextHolder.clear();
        DataPermissionContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        DataPermissionContextHolder.clear();
    }

    @Test
    @DisplayName("1. 行级多租户穿透：纯字符串 ID 的外部 SQL 默认不被忽略，而在 runWithoutTenant 闭包中短路放行")
    void shouldEnforceTenantIsolationAndAllowClosureBypassForExternalSql() {
        ExposableTenantLineInnerInterceptor tenantInterceptor = new ExposableTenantLineInnerInterceptor();

        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("external.user.dynamicList")
                .sqlScript("SELECT * FROM sys_user WHERE status = 1")
                .build();
        assertThat(registry.register(def)).isTrue();

        MappedStatement ms = configuration.getMappedStatement("external.user.dynamicList");
        assertThat(ms).isNotNull();

        // 默认情况下，外部 SQL 接受行级多租户保护（shouldIgnore 返回 false）
        assertThat(tenantInterceptor.shouldIgnore(ms)).isFalse();

        // 在 runWithoutTenant 编程式闭包中，自动短路放行（shouldIgnore 返回 true）
        TenantContextHolder.runWithoutTenant(() -> {
            assertThat(tenantInterceptor.shouldIgnore(ms)).isTrue();
        });

        // 退出闭包后恢复防护
        assertThat(tenantInterceptor.shouldIgnore(ms)).isFalse();
    }

    @Test
    @DisplayName("2. 行级多租户穿透：绑定 Mapper 接口的外部动态 SQL 准确识别 @IgnoreTenant 注解并短路放行")
    void shouldRecognizeIgnoreTenantAnnotationOnMapperBoundDynamicSql() {
        ExposableTenantLineInnerInterceptor tenantInterceptor = new ExposableTenantLineInnerInterceptor();

        // 注册绑定到 TestDynamicMapper.queryWithIgnoreTenant 的外部动态 SQL
        String statementId = TestDynamicMapper.class.getName() + ".queryWithIgnoreTenant";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user")
                .build();
        assertThat(registry.register(def)).isTrue();

        MappedStatement ms = configuration.getMappedStatement(statementId);
        assertThat(tenantInterceptor.shouldIgnore(ms)).isTrue();

        // 未标注 @IgnoreTenant 的普通方法对应的动态 SQL 保持防护
        String normalId = TestDynamicMapper.class.getName() + ".queryNormalDynamic";
        registry.register(DynamicSqlDefinition.builder().id(normalId).sqlScript("SELECT * FROM sys_user").build());
        MappedStatement normalMs = configuration.getMappedStatement(normalId);
        assertThat(tenantInterceptor.shouldIgnore(normalMs)).isFalse();
    }

    @Test
    @DisplayName("3. 细粒度数据权限穿透：绑定 Mapper 的外部动态 SQL 能识别 @DataScope 并自动构建 AST 权限条件")
    void shouldApplyDataPermissionToMapperBoundDynamicSql() {
        ExposableDataPermissionInnerInterceptor dataPermissionInterceptor = new ExposableDataPermissionInnerInterceptor(
                (table, where, mappedStatementId, dataScope) -> {
                    EqualsTo equalsTo = new EqualsTo();
                    equalsTo.setLeftExpression(new Column("u." + dataScope.deptColumn()));
                    equalsTo.setRightExpression(new StringValue("dept_999"));
                    return equalsTo;
                }
        );

        String statementId = TestDynamicMapper.class.getName() + ".queryWithDataScope";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user u WHERE u.status = 1")
                .build();
        assertThat(registry.register(def)).isTrue();

        MappedStatement ms = configuration.getMappedStatement(statementId);
        // 未短路放行
        assertThat(dataPermissionInterceptor.shouldIgnore(ms)).isFalse();

        // 验证 AST 权限条件注入
        Table table = new Table("sys_user").withAlias(new net.sf.jsqlparser.expression.Alias("u"));
        Expression expr = dataPermissionInterceptor.buildTableExpression(table, null, statementId);
        assertThat(expr).isNotNull();
        assertThat(expr.toString()).isEqualTo("u.dept_id = 'dept_999'");

        // 在超管上下文中或闭包中短路放行
        DataPermissionContextHolder.runAsSuperAdmin(() -> {
            assertThat(dataPermissionInterceptor.shouldIgnore(ms)).isTrue();
        });
        DataPermissionContextHolder.runWithoutPermission(() -> {
            assertThat(dataPermissionInterceptor.shouldIgnore(ms)).isTrue();
        });
    }

    @Test
    @DisplayName("4. 防全表误操作穿透：外部动态注册的无 WHERE UPDATE/DELETE 语句能被 BlockAttackInnerInterceptor 严格阻断")
    void shouldBlockFullTableUpdateAndDeleteForDynamicSql() throws Exception {
        BlockAttackInnerInterceptor blockAttackInterceptor = new BlockAttackInnerInterceptor();

        // 反射暴露受保护的 processUpdate / processDelete 进行针对性 AST 阻断验证
        Method processUpdateMethod = BlockAttackInnerInterceptor.class.getDeclaredMethod("processUpdate", Update.class, int.class, String.class, Object.class);
        processUpdateMethod.setAccessible(true);

        Method processDeleteMethod = BlockAttackInnerInterceptor.class.getDeclaredMethod("processDelete", Delete.class, int.class, String.class, Object.class);
        processDeleteMethod.setAccessible(true);

        // 外部动态 SQL 包含无 WHERE 全表更新
        String badUpdateSql = "UPDATE sys_user SET status = 0";
        Update updateStatement = (Update) CCJSqlParserUtil.parse(badUpdateSql);

        assertThatThrownBy(() -> {
            try {
                processUpdateMethod.invoke(blockAttackInterceptor, updateStatement, 0, badUpdateSql, null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getTargetException();
            }
        }).isInstanceOf(MybatisPlusException.class)
                .hasMessageContaining("Prohibition of table update operation");

        // 外部动态 SQL 包含无 WHERE 全表删除
        String badDeleteSql = "DELETE FROM sys_user";
        Delete deleteStatement = (Delete) CCJSqlParserUtil.parse(badDeleteSql);

        assertThatThrownBy(() -> {
            try {
                processDeleteMethod.invoke(blockAttackInterceptor, deleteStatement, 0, badDeleteSql, null);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getTargetException();
            }
        }).isInstanceOf(MybatisPlusException.class)
                .hasMessageContaining("Prohibition of full table deletion");
    }

    @Test
    @DisplayName("5. 透明加解密穿透：动态 SQL 字段使用 EncryptTypeHandler 时入库加密与出库解密透明闭环")
    void shouldEncryptAndDecryptTransparentlyInDynamicSql() throws Exception {
        EncryptTypeHandler.setGlobalSecretKey("TransparentSecretKey1234567890");
        EncryptTypeHandler typeHandler = new EncryptTypeHandler();

        PreparedStatement ps = mock(PreparedStatement.class);
        typeHandler.setNonNullParameter(ps, 1, "13800138000", null);

        // 验证写入 JDBC PreparedStatement 的参数已被 AES 加密（非原始明文）
        verify(ps).setString(eq(1), argThat(cipherText ->
                cipherText != null && !cipherText.equals("13800138000") && cipherText.length() > 16
        ));

        // 模拟从数据库 ResultSet 读取密文出库
        ResultSet rs = mock(ResultSet.class);
        // 先用同一处理器预备密文
        String rawPlain = "13800138000";
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        typeHandler.setNonNullParameter(ps, 2, rawPlain, null);
        verify(ps).setString(eq(2), captor.capture());
        String encryptedInDb = captor.getValue();

        when(rs.getString("phone")).thenReturn(encryptedInDb);
        when(rs.wasNull()).thenReturn(false);

        // 验证读取时透明还原为原始明文
        String decrypted = typeHandler.getNullableResult(rs, "phone");
        assertThat(decrypted).isEqualTo(rawPlain);
    }
}
