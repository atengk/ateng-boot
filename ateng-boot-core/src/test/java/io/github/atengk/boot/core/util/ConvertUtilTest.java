package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // 单参 toList 泛型转换
        List<Object> allItems = ConvertUtil.toList(mixed);
        assertThat(allItems).containsExactly("str", 123, "another-str");
        assertThat(ConvertUtil.toList(null)).isEmpty();

        // Iterable 与 Iterator 扩展支持
        Iterable<String> iterable = () -> List.of("i1", "i2").iterator();
        assertThat(ConvertUtil.toList(iterable, String.class)).containsExactly("i1", "i2");
        Iterator<String> iterator = List.of("it1", "it2").iterator();
        assertThat(ConvertUtil.toList(iterator, String.class)).containsExactly("it1", "it2");
    }

    @Test
    @DisplayName("集合安全去重转换 toSet 校验（保序、空白兜底不可变空集、Iterable/Iterator/数组）")
    void shouldConvertToSetSafely() {
        // null、空白字符及空集合兜底不可变空 Set
        Set<Object> fromNull = ConvertUtil.toSet(null);
        assertThat(fromNull).isEmpty();
        assertThatThrownBy(() -> fromNull.add("x")).isInstanceOf(UnsupportedOperationException.class);

        Set<String> fromEmpty = ConvertUtil.toSet("", String.class);
        assertThat(fromEmpty).isEmpty();
        assertThatThrownBy(() -> fromEmpty.add("x")).isInstanceOf(UnsupportedOperationException.class);

        Set<String> fromBlank = ConvertUtil.toSet("   ", String.class);
        assertThat(fromBlank).isEmpty();
        assertThatThrownBy(() -> fromBlank.add("x")).isInstanceOf(UnsupportedOperationException.class);

        // 异构过滤后为空也返回不可变空集
        Set<Integer> filteredEmpty = ConvertUtil.toSet(List.of("only-str"), Integer.class);
        assertThat(filteredEmpty).isEmpty();
        assertThatThrownBy(() -> filteredEmpty.add(1)).isInstanceOf(UnsupportedOperationException.class);

        // 保序去重（LinkedHashSet）
        List<Object> mixed = List.of("str", 123, "str", "another-str", 123);
        Set<String> stringSet = ConvertUtil.toSet(mixed, String.class);
        assertThat(stringSet).isInstanceOf(LinkedHashSet.class);
        assertThat(stringSet).containsExactly("str", "another-str");

        Set<Object> allSet = ConvertUtil.toSet(mixed);
        assertThat(allSet).isInstanceOf(LinkedHashSet.class);
        assertThat(allSet).containsExactly("str", 123, "another-str");

        // 数组、自定义 Iterable、Iterator 与单值包装
        assertThat(ConvertUtil.toSet(new String[]{"k1", "k2", "k1"}, String.class))
                .containsExactly("k1", "k2");
        Iterable<String> customIterable = () -> List.of("a", "b", "a").iterator();
        assertThat(ConvertUtil.toSet(customIterable, String.class)).containsExactly("a", "b");
        Iterator<String> iterator = List.of("x", "y", "x").iterator();
        assertThat(ConvertUtil.toSet(iterator, String.class)).containsExactly("x", "y");
        assertThat(ConvertUtil.toSet("single", String.class)).containsExactly("single");
    }
}
