package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型转换门面 ConvertUtil 单测
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("类型转换工具 ConvertUtil 测试")
class ConvertUtilTest {

    @Test
    @DisplayName("数值类型安全转换验证（含非法输入与默认值兜底）")
    void shouldConvertNumbersSafely() {
        // toLong
        assertThat(ConvertUtil.toLong(12345L)).isEqualTo(12345L);
        assertThat(ConvertUtil.toLong("67890")).isEqualTo(67890L);
        assertThat(ConvertUtil.toLong("123.45")).isEqualTo(123L);
        assertThat(ConvertUtil.toLong(null, -1L)).isEqualTo(-1L);
        assertThat(ConvertUtil.toLong("invalid_num", 999L)).isEqualTo(999L);
        assertThat(ConvertUtil.toLong(null)).isNull();

        // toInt
        assertThat(ConvertUtil.toInt(100)).isEqualTo(100);
        assertThat(ConvertUtil.toInt("200")).isEqualTo(200);
        assertThat(ConvertUtil.toInt("invalid", 404)).isEqualTo(404);
        assertThat(ConvertUtil.toInt(null)).isNull();

        // toDouble
        assertThat(ConvertUtil.toDouble(3.14)).isEqualTo(3.14);
        assertThat(ConvertUtil.toDouble("2.718")).isEqualTo(2.718);
        assertThat(ConvertUtil.toDouble("invalid", 0.0)).isEqualTo(0.0);
        assertThat(ConvertUtil.toDouble(null)).isNull();

        // toBigDecimal
        assertThat(ConvertUtil.toBigDecimal(12.34)).isEqualByComparingTo(BigDecimal.valueOf(12.34));
        assertThat(ConvertUtil.toBigDecimal("100.50")).isEqualTo(new BigDecimal("100.50"));
        assertThat(ConvertUtil.toBigDecimal("invalid", BigDecimal.ZERO)).isEqualTo(BigDecimal.ZERO);
        assertThat(ConvertUtil.toBigDecimal(null)).isNull();
    }

    @Test
    @DisplayName("布尔与字符串安全转换验证")
    void shouldConvertBooleanAndStringSafely() {
        // toBoolean
        assertThat(ConvertUtil.toBoolean(true)).isTrue();
        assertThat(ConvertUtil.toBoolean("true")).isTrue();
        assertThat(ConvertUtil.toBoolean("1")).isTrue();
        assertThat(ConvertUtil.toBoolean("yes")).isTrue();
        assertThat(ConvertUtil.toBoolean("Y")).isTrue();

        assertThat(ConvertUtil.toBoolean(false)).isFalse();
        assertThat(ConvertUtil.toBoolean("false")).isFalse();
        assertThat(ConvertUtil.toBoolean("0")).isFalse();
        assertThat(ConvertUtil.toBoolean("no")).isFalse();

        assertThat(ConvertUtil.toBoolean("unknown", true)).isTrue();
        assertThat(ConvertUtil.toBoolean(null)).isNull();

        // toStr
        assertThat(ConvertUtil.toStr(123)).isEqualTo("123");
        assertThat(ConvertUtil.toStr(null, "fallback")).isEqualTo("fallback");
        assertThat(ConvertUtil.toStr(null)).isNull();
    }

    @Test
    @DisplayName("异构集合、数组与单元素转 List 验证")
    void shouldConvertToListSafely() {
        // null 保底返回空集合，严禁返回 null
        List<String> nullList = ConvertUtil.toList(null, String.class);
        assertThat(nullList).isEmpty();

        // Set 转 List
        List<String> fromSet = ConvertUtil.toList(Set.of("A", "B"), String.class);
        assertThat(fromSet).containsExactlyInAnyOrder("A", "B");

        // 数组转 List
        Integer[] array = new Integer[]{1, 2, 3};
        List<Integer> fromArray = ConvertUtil.toList(array, Integer.class);
        assertThat(fromArray).containsExactly(1, 2, 3);

        // 单对象转 List
        List<String> single = ConvertUtil.toList("single-item", String.class);
        assertThat(single).containsExactly("single-item");

        // 异构类型安全过滤，杜绝 ClassCastException
        List<Object> mixed = List.of("str", 123, "another-str");
        List<String> filtered = ConvertUtil.toList(mixed, String.class);
        assertThat(filtered).containsExactly("str", "another-str");
    }
}
