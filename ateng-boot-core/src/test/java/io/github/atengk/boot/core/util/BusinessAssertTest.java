package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    @DisplayName("测试 {} 占位符惰性格式化")
    void shouldFormatMessageWithPlaceholdersLazily() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.isTrue(false, "用户 {} 状态非法: {}", 1001L, "LOCKED"));
        assertEquals("用户 1001 状态非法: LOCKED", ex.getMsg());

        // 测试不包含占位符时直接返回原串
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                BusinessAssert.notNull(null, "参数不可为空", "ignored1", "ignored2"));
        assertEquals("参数不可为空", ex2.getMsg());

        // 测试占位符多于参数时按需截断
        BusinessException ex3 = assertThrows(BusinessException.class, () ->
                BusinessAssert.hasText(null, "{} 与 {}", "A"));
        assertEquals("A 与 {}", ex3.getMsg());
    }

    @Test
    @DisplayName("测试 Supplier 延迟构建错误信息（仅在失败时触发计算）")
    void shouldEvaluateMessageSupplierOnlyOnFailure() {
        AtomicBoolean evaluated = new AtomicBoolean(false);

        // 成功时 Supplier 绝不执行
        BusinessAssert.isTrue(true, () -> {
            evaluated.set(true);
            return "不应执行";
        });
        assertFalse(evaluated.get(), "断言成立时 Supplier 不应被计算");

        // 失败时 Supplier 触发求值
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.isTrue(false, () -> {
                    evaluated.set(true);
                    return "动态延迟计算的错误信息";
                }));
        assertTrue(evaluated.get());
        assertEquals("动态延迟计算的错误信息", ex.getMsg());
    }

    @Test
    @DisplayName("测试数值与区间断言 (gt, gte, lt, lte, between)")
    void shouldAssertNumericalAndRangeConditions() {
        // gt (严格大于)
        assertDoesNotThrow(() -> BusinessAssert.gt(10, 5, "应当大于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.gt(5, 5, "应当大于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.gt(3, 5, "应当大于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.gt(null, 5, "应当大于 5"));

        // gte (大于等于)
        assertDoesNotThrow(() -> BusinessAssert.gte(10, 5, "应当大于等于 5"));
        assertDoesNotThrow(() -> BusinessAssert.gte(5, 5, "应当大于等于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.gte(4, 5, "应当大于等于 5"));

        // lt (严格小于)
        assertDoesNotThrow(() -> BusinessAssert.lt(3, 5, "应当小于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.lt(5, 5, "应当小于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.lt(6, 5, "应当小于 5"));

        // lte (小于等于)
        assertDoesNotThrow(() -> BusinessAssert.lte(3, 5, "应当小于等于 5"));
        assertDoesNotThrow(() -> BusinessAssert.lte(5, 5, "应当小于等于 5"));
        assertThrows(BusinessException.class, () -> BusinessAssert.lte(6, 5, "应当小于等于 5"));

        // between (闭区间)
        assertDoesNotThrow(() -> BusinessAssert.between(5, 1, 10, "应当在 1~10 之间"));
        assertDoesNotThrow(() -> BusinessAssert.between(1, 1, 10, "应当在 1~10 之间"));
        assertDoesNotThrow(() -> BusinessAssert.between(10, 1, 10, "应当在 1~10 之间"));
        assertThrows(BusinessException.class, () -> BusinessAssert.between(0, 1, 10, "应当在 1~10 之间"));
        assertThrows(BusinessException.class, () -> BusinessAssert.between(11, 1, 10, "应当在 1~10 之间"));
        // 倒置区间直接拦截
        assertThrows(BusinessException.class, () -> BusinessAssert.between(5, 10, 1, "非法区间应当失败"));

        // 验证占位符格式化
        BusinessException ex = assertThrows(BusinessException.class, () ->
                BusinessAssert.between(15, 1, 10, "数值 {} 超出区间 [{}, {}]", 15, 1, 10));
        assertEquals("数值 15 超出区间 [1, 10]", ex.getMsg());
    }
}
