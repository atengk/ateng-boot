package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字符串工具单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("字符串通用处理工具 StringUtil 测试")
class StringUtilTest {

    @Test
    @DisplayName("空值与文本判断能力验证")
    void shouldCheckEmptyAndBlankCorrectly() {
        assertThat(StringUtil.hasText("hello")).isTrue();
        assertThat(StringUtil.hasText("   ")).isFalse();
        assertThat(StringUtil.hasText(null)).isFalse();

        assertThat(StringUtil.isEmpty("")).isTrue();
        assertThat(StringUtil.isEmpty(null)).isTrue();
        assertThat(StringUtil.isEmpty("   ")).isFalse();

        assertThat(StringUtil.isNotEmpty("hello")).isTrue();
        assertThat(StringUtil.isNotEmpty("")).isFalse();

        assertThat(StringUtil.isBlank("   ")).isTrue();
        assertThat(StringUtil.isBlank("")).isTrue();
        assertThat(StringUtil.isBlank(null)).isTrue();
        assertThat(StringUtil.isBlank(" a ")).isFalse();

        assertThat(StringUtil.isNotBlank(" a ")).isTrue();
    }

    @Test
    @DisplayName("format 高性能占位符替换验证（含转义与边界）")
    void shouldFormatTemplateCorrectly() {
        assertThat(StringUtil.format(null, "arg")).isNull();
        assertThat(StringUtil.format("hello world")).isEqualTo("hello world");
        assertThat(StringUtil.format("hello {}, welcome to {}", "Ateng", "Beijing"))
                .isEqualTo("hello Ateng, welcome to Beijing");

        // 占位符多于参数：多余占位符保留
        assertThat(StringUtil.format("user {} from {} with age {}", "admin", "CN"))
                .isEqualTo("user admin from CN with age {}");

        // 参数多于占位符：多余参数安全忽略
        assertThat(StringUtil.format("hello {}", "A", "B", "C"))
                .isEqualTo("hello A");

        // 转义字符验证
        assertThat(StringUtil.format("show literal \\{} and value {}", "real"))
                .isEqualTo("show literal {} and value real");
    }

    @Test
    @DisplayName("下划线与驼峰命名双向互转验证")
    void shouldConvertBetweenCamelCaseAndSnakeCase() {
        // 下划线转小驼峰
        assertThat(StringUtil.toCamelCase(null)).isNull();
        assertThat(StringUtil.toCamelCase("")).isEqualTo("");
        assertThat(StringUtil.toCamelCase("user_order_id")).isEqualTo("userOrderId");
        assertThat(StringUtil.toCamelCase("USER_ORDER_ID")).isEqualTo("userOrderId");
        assertThat(StringUtil.toCamelCase("userName")).isEqualTo("userName");
        assertThat(StringUtil.toCamelCase("UserName")).isEqualTo("userName");
        assertThat(StringUtil.toCamelCase("_leading_underline")).isEqualTo("leadingUnderline");

        // 驼峰转下划线
        assertThat(StringUtil.toUnderlineCase(null)).isNull();
        assertThat(StringUtil.toUnderlineCase("")).isEqualTo("");
        assertThat(StringUtil.toUnderlineCase("userOrderId")).isEqualTo("user_order_id");
        assertThat(StringUtil.toUnderlineCase("UserOrderId")).isEqualTo("user_order_id");
        assertThat(StringUtil.toUnderlineCase("HTTPRequest")).isEqualTo("http_request");
        assertThat(StringUtil.toSnakeCase("userOrderId")).isEqualTo("user_order_id");
    }

    @Test
    @DisplayName("truncate: 安全文本截断")
    void shouldTruncateCorrectly() {
        assertThat(StringUtil.truncate(null, 5)).isNull();
        assertThat(StringUtil.truncate("hello", 0)).isEqualTo("");
        assertThat(StringUtil.truncate("hello", -1)).isEqualTo("");
        assertThat(StringUtil.truncate("hello", 3)).isEqualTo("hel");
        assertThat(StringUtil.truncate("hello", 10)).isEqualTo("hello");
    }

    @Test
    @DisplayName("containsAny 与 containsIgnoreCase 检索断言")
    void shouldCheckContainsCorrectly() {
        assertThat(StringUtil.containsAny("apple,banana,orange", "pear", "banana")).isTrue();
        assertThat(StringUtil.containsAny("apple,banana", "watermelon", "grape")).isFalse();
        assertThat(StringUtil.containsAny(null, "test")).isFalse();
        assertThat(StringUtil.containsAny("apple")).isFalse();

        assertThat(StringUtil.containsIgnoreCase("HelloWorld", "world")).isTrue();
        assertThat(StringUtil.containsIgnoreCase("HelloWorld", "WORLD")).isTrue();
        assertThat(StringUtil.containsIgnoreCase("HelloWorld", "java")).isFalse();
        assertThat(StringUtil.containsIgnoreCase(null, "java")).isFalse();
        assertThat(StringUtil.containsIgnoreCase("hello", null)).isFalse();
    }

    @Test
    @DisplayName("mask 动态掩码委托测试")
    void shouldMaskCorrectly() {
        assertThat(StringUtil.mask("13812345678", 3, 7)).isEqualTo("138****5678");
        assertThat(StringUtil.mask(null, 1, 2)).isNull();
    }
}
