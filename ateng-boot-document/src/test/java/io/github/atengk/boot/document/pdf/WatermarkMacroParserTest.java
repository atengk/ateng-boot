package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 水印动态宏变量解析器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class WatermarkMacroParserTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试用户宏变量解析与手机号自动脱敏掩码")
    void testUserMacrosAndPhoneDesensitization() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("user", Map.of(
                "nickname", "李四",
                "username", "lisi",
                "phone", "13812345678"
        ));

        String template = "操作人：#{user.nickname} (#{user.username}) 手机：#{user.phone}";
        String result = WatermarkMacroParser.parse(template, vars);

        assertThat(result).isEqualTo("操作人：李四 (lisi) 手机：138****5678");
    }

    @Test
    @DisplayName("测试短手机号及边界手机号脱敏")
    void testShortPhoneDesensitization() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("user", Map.of("phone", "1234567"));

        String template = "#{user.phone}";
        String result = WatermarkMacroParser.parse(template, vars);

        assertThat(result).isEqualTo("1*****7");
    }

    @Test
    @DisplayName("测试 MDC traceId 与时间宏函数解析")
    void testTraceIdAndNowMacro() {
        MDC.put("traceId", "trace-999999");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String template = "Trace: #{traceId} 日期: #{now('yyyy-MM-dd')}";
        String result = WatermarkMacroParser.parse(template, null);

        assertThat(result).isEqualTo("Trace: trace-999999 日期: " + today);
    }

    @Test
    @DisplayName("测试默认值语法与缺省变量回退")
    void testDefaultValueFallback() {
        String template = "昵称: #{user.nickname!'匿名'} 手机: #{user.phone!'无'} Trace: #{traceId!''}";
        String result = WatermarkMacroParser.parse(template, null);

        assertThat(result).isEqualTo("昵称: 匿名 手机: 无 Trace: ");
    }

    @Test
    @DisplayName("测试未传 user 时从 UserContextHolder 兜底提取")
    void testFallbackToUserContextHolder() {
        UserContextHolder.set("ctx-admin-user");

        String template = "操作人: #{user.nickname}";
        String result = WatermarkMacroParser.parse(template, null);

        assertThat(result).isEqualTo("操作人: ctx-admin-user");
    }

    @Test
    @DisplayName("测试 #{user.name}、#{user.code} 与 #{tenantId} / #{tenant_id} 上下文解析")
    void testUserNameCodeAndTenantMacros() {
        TenantContextHolder.setTenantId("tenant-007");
        UserContextHolder.set("ctx-user");

        Map<String, Object> explicitVars = new HashMap<>();
        explicitVars.put("user", Map.of(
                "name", "张三",
                "code", "ZS_001"
        ));
        assertThat(WatermarkMacroParser.parse("#{user.name}-#{user.code}-#{tenantId}-#{tenant_id}", explicitVars))
                .isEqualTo("张三-ZS_001-tenant-007-tenant-007");

        // 显式 user map 仅有 nickname/username 时，user.name 回退到 nickname，user.code 回退到 username
        Map<String, Object> aliasVars = new HashMap<>();
        aliasVars.put("user", Map.of(
                "nickname", "王五",
                "username", "wangwu"
        ));
        aliasVars.put("tenantId", "override-tenant");
        assertThat(WatermarkMacroParser.parse("#{user.name}-#{user.code}-#{tenantId}", aliasVars))
                .isEqualTo("王五-wangwu-override-tenant");

        // 无 variables 时 #{user.name} 回退至 UserContextHolder，#{tenantId} 回退至 TenantContextHolder
        assertThat(WatermarkMacroParser.parse("#{user.name}-#{tenantId}", null))
                .isEqualTo("ctx-user-tenant-007");

        TenantContextHolder.clear();
        UserContextHolder.clear();
        assertThat(WatermarkMacroParser.parse("#{user.name!'匿名'}-#{tenantId!'默认租户'}", null))
                .isEqualTo("匿名-默认租户");
    }
}
