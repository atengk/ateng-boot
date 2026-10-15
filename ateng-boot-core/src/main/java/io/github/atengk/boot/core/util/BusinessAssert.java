package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * 语义化业务前置断言工具
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class BusinessAssert {

    private BusinessAssert() {
    }

    /**
     * 断言表达式必须为 true
     *
     * @param expression 待校验表达式
     * @param msg        断言失败提示文案
     * @throws BusinessException 当表达式为 false 时抛出
     */
    public static void isTrue(boolean expression, String msg) {
        if (!expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言表达式必须为 true
     *
     * @param expression 待校验表达式
     * @param code       断言失败响应码
     * @throws BusinessException 当表达式为 false 时抛出
     */
    public static void isTrue(boolean expression, IResultCode code) {
        if (!expression) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言表达式必须为 false
     *
     * @param expression 待校验表达式
     * @param msg        断言失败提示文案
     * @throws BusinessException 当表达式为 true 时抛出
     */
    public static void isFalse(boolean expression, String msg) {
        if (expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言表达式必须为 false
     *
     * @param expression 待校验表达式
     * @param code       断言失败响应码
     * @throws BusinessException 当表达式为 true 时抛出
     */
    public static void isFalse(boolean expression, IResultCode code) {
        if (expression) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标对象不可为 null
     *
     * @param object 待校验对象
     * @param msg    断言失败提示文案
     * @throws BusinessException 当对象为 null 时抛出
     */
    public static void notNull(Object object, String msg) {
        if (object == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标对象不可为 null
     *
     * @param object 待校验对象
     * @param code   断言失败响应码
     * @throws BusinessException 当对象为 null 时抛出
     */
    public static void notNull(Object object, IResultCode code) {
        if (object == null) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标对象必须为 null
     *
     * @param object 待校验对象
     * @param msg    断言失败提示文案
     * @throws BusinessException 当对象不为 null 时抛出
     */
    public static void isNull(Object object, String msg) {
        if (object != null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标对象必须为 null
     *
     * @param object 待校验对象
     * @param code   断言失败响应码
     * @throws BusinessException 当对象不为 null 时抛出
     */
    public static void isNull(Object object, IResultCode code) {
        if (object != null) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标文本必须包含有效非空白字符
     *
     * @param text 待校验文本
     * @param msg  断言失败提示文案
     * @throws BusinessException 当文本为 null、空串或全空白时抛出
     */
    public static void hasText(String text, String msg) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标文本必须包含有效非空白字符
     *
     * @param text 待校验文本
     * @param code 断言失败响应码
     * @throws BusinessException 当文本为 null、空串或全空白时抛出
     */
    public static void hasText(String text, IResultCode code) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言集合容器不可为 null 且不可为空
     *
     * @param collection 待校验集合
     * @param msg        断言失败提示文案
     * @throws BusinessException 当集合为 null 或为空时抛出
     */
    public static void notEmpty(Collection<?> collection, String msg) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言集合容器不可为 null 且不可为空
     *
     * @param collection 待校验集合
     * @param code       断言失败响应码
     * @throws BusinessException 当集合为 null 或为空时抛出
     */
    public static void notEmpty(Collection<?> collection, IResultCode code) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言集合容器必须为 null 或为空
     *
     * @param collection 待校验集合
     * @param msg        断言失败提示文案
     * @throws BusinessException 当集合非空时抛出
     */
    public static void isEmpty(Collection<?> collection, String msg) {
        if (collection != null && !collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言集合容器必须为 null 或为空
     *
     * @param collection 待校验集合
     * @param code       断言失败响应码
     * @throws BusinessException 当集合非空时抛出
     */
    public static void isEmpty(Collection<?> collection, IResultCode code) {
        if (collection != null && !collection.isEmpty()) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言 Map 容器不可为 null 且不可为空
     *
     * @param map 待校验 Map
     * @param msg 断言失败提示文案
     * @throws BusinessException 当 Map 为 null 或为空时抛出
     */
    public static void notEmpty(Map<?, ?> map, String msg) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言 Map 容器不可为 null 且不可为空
     *
     * @param map  待校验 Map
     * @param code 断言失败响应码
     * @throws BusinessException 当 Map 为 null 或为空时抛出
     */
    public static void notEmpty(Map<?, ?> map, IResultCode code) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言 Map 容器必须为 null 或为空
     *
     * @param map 待校验 Map
     * @param msg 断言失败提示文案
     * @throws BusinessException 当 Map 非空时抛出
     */
    public static void isEmpty(Map<?, ?> map, String msg) {
        if (map != null && !map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言 Map 容器必须为 null 或为空
     *
     * @param map  待校验 Map
     * @param code 断言失败响应码
     * @throws BusinessException 当 Map 非空时抛出
     */
    public static void isEmpty(Map<?, ?> map, IResultCode code) {
        if (map != null && !map.isEmpty()) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言数组容器不可为 null 且不可为空
     *
     * @param array 待校验数组
     * @param msg   断言失败提示文案
     * @throws BusinessException 当数组为 null 或长度为 0 时抛出
     */
    public static void notEmpty(Object[] array, String msg) {
        if (array == null || array.length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言数组容器不可为 null 且不可为空
     *
     * @param array 待校验数组
     * @param code  断言失败响应码
     * @throws BusinessException 当数组为 null 或长度为 0 时抛出
     */
    public static void notEmpty(Object[] array, IResultCode code) {
        if (array == null || array.length == 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言数组容器必须为 null 或长度为 0
     *
     * @param array 待校验数组
     * @param msg   断言失败提示文案
     * @throws BusinessException 当数组非空时抛出
     */
    public static void isEmpty(Object[] array, String msg) {
        if (array != null && array.length > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言数组容器必须为 null 或长度为 0
     *
     * @param array 待校验数组
     * @param code  断言失败响应码
     * @throws BusinessException 当数组非空时抛出
     */
    public static void isEmpty(Object[] array, IResultCode code) {
        if (array != null && array.length > 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言两个对象在语义上必须相等（通过 Objects.equals 判断）
     *
     * @param expected 预期对象
     * @param actual   实际对象
     * @param msg      断言失败提示文案
     * @throws BusinessException 当两者不相等时抛出
     */
    public static void equals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言两个对象在语义上必须相等（通过 Objects.equals 判断）
     *
     * @param expected 预期对象
     * @param actual   实际对象
     * @param code     断言失败响应码
     * @throws BusinessException 当两者不相等时抛出
     */
    public static void equals(Object expected, Object actual, IResultCode code) {
        if (!Objects.equals(expected, actual)) {
            throw new BusinessException(code);
        }
    }
}
