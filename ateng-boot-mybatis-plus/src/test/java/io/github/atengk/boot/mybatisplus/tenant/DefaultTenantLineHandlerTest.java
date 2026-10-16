package io.github.atengk.boot.mybatisplus.tenant;

import io.github.atengk.boot.mybatisplus.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultTenantLineHandler 单元测试
 * 验证租户列名解析、忽略表白名单比对、JSqlParser 表达式自适应构建（LongValue vs StringValue）
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("DefaultTenantLineHandler 租户行级处理器单元测试")
class DefaultTenantLineHandlerTest {

    @BeforeEach
    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 租户列名解析：支持默认 tenant_id 与自定义列名覆盖")
    void shouldResolveTenantColumn() {
        MybatisPlusProperties.TenantProperties defaultProps = new MybatisPlusProperties.TenantProperties();
        DefaultTenantLineHandler defaultHandler = new DefaultTenantLineHandler(defaultProps);
        assertThat(defaultHandler.getTenantIdColumn()).isEqualTo("tenant_id");

        MybatisPlusProperties.TenantProperties customProps = new MybatisPlusProperties.TenantProperties();
        customProps.setColumn("company_id");
        DefaultTenantLineHandler customHandler = new DefaultTenantLineHandler(customProps);
        assertThat(customHandler.getTenantIdColumn()).isEqualTo("company_id");
    }

    @Test
    @DisplayName("2. 忽略表白名单：忽略大小写并剥离反引号与双引号")
    void shouldMatchIgnoreTablesSafely() {
        MybatisPlusProperties.TenantProperties props = new MybatisPlusProperties.TenantProperties();
        props.setIgnoreTables(List.of("sys_dict", "SYS_LOG", "public_config"));
        DefaultTenantLineHandler handler = new DefaultTenantLineHandler(props);

        // 匹配普通表名（不区分大小写）
        assertThat(handler.ignoreTable("sys_dict")).isTrue();
        assertThat(handler.ignoreTable("SYS_DICT")).isTrue();
        assertThat(handler.ignoreTable("sys_log")).isTrue();

        // 匹配带数据库反引号与双引号的表名
        assertThat(handler.ignoreTable("`sys_dict`")).isTrue();
        assertThat(handler.ignoreTable("\"public_config\"")).isTrue();

        // 未在白名单中的表返回 false
        assertThat(handler.ignoreTable("sys_user")).isFalse();
        assertThat(handler.ignoreTable("sys_order")).isFalse();
        assertThat(handler.ignoreTable(null)).isFalse();
    }

    @Test
    @DisplayName("3. 表达式智能推断：纯数字生成 LongValue，字母字符生成 StringValue，空租户返回 NullValue")
    void shouldBuildAdaptiveExpression() {
        MybatisPlusProperties.TenantProperties props = new MybatisPlusProperties.TenantProperties();
        DefaultTenantLineHandler handler = new DefaultTenantLineHandler(props);

        // 3.1 纯数字租户 ID -> LongValue
        TenantContextHolder.setTenantId("10086");
        Expression exprLong = handler.getTenantId();
        assertThat(exprLong).isInstanceOf(LongValue.class);
        assertThat(((LongValue) exprLong).getValue()).isEqualTo(10086L);

        // 3.2 字符串租户 ID -> StringValue
        TenantContextHolder.setTenantId("tenant_corp_888");
        Expression exprStr = handler.getTenantId();
        assertThat(exprStr).isInstanceOf(StringValue.class);
        assertThat(((StringValue) exprStr).getValue()).isEqualTo("tenant_corp_888");

        // 3.3 未设置租户 ID -> NullValue
        TenantContextHolder.clear();
        Expression exprNull = handler.getTenantId();
        assertThat(exprNull).isInstanceOf(NullValue.class);
    }
}
