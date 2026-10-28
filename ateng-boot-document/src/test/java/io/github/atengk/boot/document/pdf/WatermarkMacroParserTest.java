package io.github.atengk.boot.document.pdf;

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
        UserContextHolder.set(null);
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
}
