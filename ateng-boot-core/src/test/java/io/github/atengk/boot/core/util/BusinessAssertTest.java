package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义化业务断言工具测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("语义化业务断言工具测试")
class BusinessAssertTest {

    @Test
    @DisplayName("isTrue 断言成立正常通过，不成立抛出异常")
    void shouldAssertIsTrueCorrectly() {
        assertDoesNotThrow(() -> BusinessAssert.isTrue(true, "应当为真"));

        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                BusinessAssert.isTrue(false, "条件未达标"));
        assertEquals("条件未达标", ex1.getMsg());
        assertEquals(400, ex1.getCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                BusinessAssert.isTrue(false, CommonResultCode.BAD_REQUEST));
        assertEquals(400, ex2.getCode());
    }

    @Test
    @DisplayName("isFalse 断言成立正常通过，不成立抛出异常")
    void shouldAssertIsFalseCorrectly() {
        assertDoesNotThrow(() -> BusinessAssert.isFalse(false, "应当为假"));

        assertThrows(BusinessException.class, () ->
                BusinessAssert.isFalse(true, "不能为真"));
    }

    @Test
    @DisplayName("notNull 与 isNull 校验对象引用")
    void shouldAssertNotNullAndIsNull() {
        assertDoesNotThrow(() -> BusinessAssert.notNull("valid", "不能为 null"));
        assertThrows(BusinessException.class, () -> BusinessAssert.notNull(null, "参数不可为空"));

        assertDoesNotThrow(() -> BusinessAssert.isNull(null, "必须为 null"));
        assertThrows(BusinessException.class, () -> BusinessAssert.isNull("not-null", "必须为 null"));
    }

    @Test
    @DisplayName("hasText 校验有效非空白字符串")
    void shouldAssertHasText() {
        assertDoesNotThrow(() -> BusinessAssert.hasText("ateng", "必须包含有效文本"));

        assertThrows(BusinessException.class, () -> BusinessAssert.hasText(null, "文本不可为空"));
        assertThrows(BusinessException.class, () -> BusinessAssert.hasText("", "文本不可为空串"));
        assertThrows(BusinessException.class, () -> BusinessAssert.hasText("   ", "文本不可全为空格"));
    }

    @Test
    @DisplayName("notEmpty 与 isEmpty 校验集合容器")
    void shouldAssertCollectionEmptyAndNotEmpty() {
        List<String> list = List.of("item");
        assertDoesNotThrow(() -> BusinessAssert.notEmpty(list, "集合不可为空"));
        assertThrows(BusinessException.class, () -> BusinessAssert.notEmpty(Collections.emptyList(), "集合不可为空"));
        assertThrows(BusinessException.class, () -> BusinessAssert.notEmpty((List<?>) null, "集合不可为空"));

        assertDoesNotThrow(() -> BusinessAssert.isEmpty(Collections.emptyList(), "应当为空集合"));
        assertThrows(BusinessException.class, () -> BusinessAssert.isEmpty(list, "应当为空集合"));
    }

    @Test
    @DisplayName("notEmpty 与 isEmpty 校验 Map 容器")
    void shouldAssertMapEmptyAndNotEmpty() {
        Map<String, String> map = Map.of("k", "v");
        assertDoesNotThrow(() -> BusinessAssert.notEmpty(map, "Map 不可为空"));
        assertThrows(BusinessException.class, () -> BusinessAssert.notEmpty(Collections.emptyMap(), "Map 不可为空"));

        assertDoesNotThrow(() -> BusinessAssert.isEmpty(Collections.emptyMap(), "应当为空 Map"));
        assertThrows(BusinessException.class, () -> BusinessAssert.isEmpty(map, "应当为空 Map"));
    }

    @Test
    @DisplayName("notEmpty 与 isEmpty 校验数组容器")
    void shouldAssertArrayEmptyAndNotEmpty() {
        String[] array = new String[]{"elem"};
        assertDoesNotThrow(() -> BusinessAssert.notEmpty(array, "数组不可为空"));
        assertThrows(BusinessException.class, () -> BusinessAssert.notEmpty(new String[0], "数组不可为空"));

        assertDoesNotThrow(() -> BusinessAssert.isEmpty(new String[0], "应当为空数组"));
        assertThrows(BusinessException.class, () -> BusinessAssert.isEmpty(array, "应当为空数组"));
    }

    @Test
    @DisplayName("equals 校验对象等值性")
    void shouldAssertEquals() {
        assertDoesNotThrow(() -> BusinessAssert.equals("A", "A", "两者应当相等"));
        assertThrows(BusinessException.class, () -> BusinessAssert.equals("A", "B", "两者不相等"));
    }
}
