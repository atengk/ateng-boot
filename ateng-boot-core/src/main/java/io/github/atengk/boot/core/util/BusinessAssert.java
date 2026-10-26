package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 语义化业务前置断言工具
 * 支持 {} 占位符惰性格式化、Supplier 延迟计算、基础类型与集合校验及数值区间断言
 *
 * @author Ateng
 * @since 2026-10-15
 */
public final class BusinessAssert {

    private BusinessAssert() {
    }

    // ==================== isTrue ====================

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
     * 断言表达式必须为 true（支持 {} 占位符惰性格式化）
     *
     * @param expression 待校验表达式
     * @param msg        断言失败提示文案模板
     * @param args       占位符替换参数
     * @throws BusinessException 当表达式为 false 时抛出
     */
    public static void isTrue(boolean expression, String msg, Object... args) {
        if (!expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言表达式必须为 true（支持 Supplier 延迟构建错误信息）
     *
     * @param expression      待校验表达式
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当表达式为 false 时抛出
     */
    public static void isTrue(boolean expression, Supplier<String> messageSupplier) {
        if (!expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== isFalse ====================

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
     * 断言表达式必须为 false（支持 {} 占位符惰性格式化）
     *
     * @param expression 待校验表达式
     * @param msg        断言失败提示文案模板
     * @param args       占位符替换参数
     * @throws BusinessException 当表达式为 true 时抛出
     */
    public static void isFalse(boolean expression, String msg, Object... args) {
        if (expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言表达式必须为 false（支持 Supplier 延迟构建错误信息）
     *
     * @param expression      待校验表达式
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当表达式为 true 时抛出
     */
    public static void isFalse(boolean expression, Supplier<String> messageSupplier) {
        if (expression) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== notNull ====================

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
     * 断言目标对象不可为 null（支持 {} 占位符惰性格式化）
     *
     * @param object 待校验对象
     * @param msg    断言失败提示文案模板
     * @param args   占位符替换参数
     * @throws BusinessException 当对象为 null 时抛出
     */
    public static void notNull(Object object, String msg, Object... args) {
        if (object == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言目标对象不可为 null（支持 Supplier 延迟构建错误信息）
     *
     * @param object          待校验对象
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当对象为 null 时抛出
     */
    public static void notNull(Object object, Supplier<String> messageSupplier) {
        if (object == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== isNull ====================

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
     * 断言目标对象必须为 null（支持 {} 占位符惰性格式化）
     *
     * @param object 待校验对象
     * @param msg    断言失败提示文案模板
     * @param args   占位符替换参数
     * @throws BusinessException 当对象不为 null 时抛出
     */
    public static void isNull(Object object, String msg, Object... args) {
        if (object != null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言目标对象必须为 null（支持 Supplier 延迟构建错误信息）
     *
     * @param object          待校验对象
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当对象不为 null 时抛出
     */
    public static void isNull(Object object, Supplier<String> messageSupplier) {
        if (object != null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== hasText ====================

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
     * 断言目标文本必须包含有效非空白字符（支持 {} 占位符惰性格式化）
     *
     * @param text 待校验文本
     * @param msg  断言失败提示文案模板
     * @param args 占位符替换参数
     * @throws BusinessException 当文本为 null、空串或全空白时抛出
     */
    public static void hasText(String text, String msg, Object... args) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言目标文本必须包含有效非空白字符（支持 Supplier 延迟构建错误信息）
     *
     * @param text            待校验文本
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当文本为 null、空串或全空白时抛出
     */
    public static void hasText(String text, Supplier<String> messageSupplier) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== notEmpty (Collection) ====================

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
     * 断言集合容器不可为 null 且不可为空（支持 {} 占位符惰性格式化）
     *
     * @param collection 待校验集合
     * @param msg        断言失败提示文案模板
     * @param args       占位符替换参数
     * @throws BusinessException 当集合为 null 或为空时抛出
     */
    public static void notEmpty(Collection<?> collection, String msg, Object... args) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言集合容器不可为 null 且不可为空（支持 Supplier 延迟构建错误信息）
     *
     * @param collection      待校验集合
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当集合为 null 或为空时抛出
     */
    public static void notEmpty(Collection<?> collection, Supplier<String> messageSupplier) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== isEmpty (Collection) ====================

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
     * 断言集合容器必须为 null 或为空（支持 {} 占位符惰性格式化）
     *
     * @param collection 待校验集合
     * @param msg        断言失败提示文案模板
     * @param args       占位符替换参数
     * @throws BusinessException 当集合非空时抛出
     */
    public static void isEmpty(Collection<?> collection, String msg, Object... args) {
        if (collection != null && !collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言集合容器必须为 null 或为空（支持 Supplier 延迟构建错误信息）
     *
     * @param collection      待校验集合
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当集合非空时抛出
     */
    public static void isEmpty(Collection<?> collection, Supplier<String> messageSupplier) {
        if (collection != null && !collection.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== notEmpty (Map) ====================

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
     * 断言 Map 容器不可为 null 且不可为空（支持 {} 占位符惰性格式化）
     *
     * @param map  待校验 Map
     * @param msg  断言失败提示文案模板
     * @param args 占位符替换参数
     * @throws BusinessException 当 Map 为 null 或为空时抛出
     */
    public static void notEmpty(Map<?, ?> map, String msg, Object... args) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言 Map 容器不可为 null 且不可为空（支持 Supplier 延迟构建错误信息）
     *
     * @param map             待校验 Map
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当 Map 为 null 或为空时抛出
     */
    public static void notEmpty(Map<?, ?> map, Supplier<String> messageSupplier) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== isEmpty (Map) ====================

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
     * 断言 Map 容器必须为 null 或为空（支持 {} 占位符惰性格式化）
     *
     * @param map  待校验 Map
     * @param msg  断言失败提示文案模板
     * @param args 占位符替换参数
     * @throws BusinessException 当 Map 非空时抛出
     */
    public static void isEmpty(Map<?, ?> map, String msg, Object... args) {
        if (map != null && !map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言 Map 容器必须为 null 或为空（支持 Supplier 延迟构建错误信息）
     *
     * @param map             待校验 Map
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当 Map 非空时抛出
     */
    public static void isEmpty(Map<?, ?> map, Supplier<String> messageSupplier) {
        if (map != null && !map.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== notEmpty (Array) ====================

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
     * 断言数组容器不可为 null 且不可为空（支持 {} 占位符惰性格式化）
     *
     * @param array 待校验数组
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @throws BusinessException 当数组为 null 或长度为 0 时抛出
     */
    public static void notEmpty(Object[] array, String msg, Object... args) {
        if (array == null || array.length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言数组容器不可为 null 且不可为空（支持 Supplier 延迟构建错误信息）
     *
     * @param array           待校验数组
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当数组为 null 或长度为 0 时抛出
     */
    public static void notEmpty(Object[] array, Supplier<String> messageSupplier) {
        if (array == null || array.length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== isEmpty (Array) ====================

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
     * 断言数组容器必须为 null 或长度为 0（支持 {} 占位符惰性格式化）
     *
     * @param array 待校验数组
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @throws BusinessException 当数组非空时抛出
     */
    public static void isEmpty(Object[] array, String msg, Object... args) {
        if (array != null && array.length > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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
     * 断言数组容器必须为 null 或长度为 0（支持 Supplier 延迟构建错误信息）
     *
     * @param array           待校验数组
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当数组非空时抛出
     */
    public static void isEmpty(Object[] array, Supplier<String> messageSupplier) {
        if (array != null && array.length > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== equals ====================

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
     * 断言两个对象在语义上必须相等（支持 {} 占位符惰性格式化）
     *
     * @param expected 预期对象
     * @param actual   实际对象
     * @param msg      断言失败提示文案模板
     * @param args     占位符替换参数
     * @throws BusinessException 当两者不相等时抛出
     */
    public static void equals(Object expected, Object actual, String msg, Object... args) {
        if (!Objects.equals(expected, actual)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
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

    /**
     * 断言两个对象在语义上必须相等（支持 Supplier 延迟构建错误信息）
     *
     * @param expected        预期对象
     * @param actual          实际对象
     * @param messageSupplier 错误信息构建函数
     * @throws BusinessException 当两者不相等时抛出
     */
    public static void equals(Object expected, Object actual, Supplier<String> messageSupplier) {
        if (!Objects.equals(expected, actual)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== 数值与区间断言 (gt, gte, lt, lte, between) ====================

    /**
     * 断言目标值必须严格大于基准值 (value > min)
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param msg   断言失败提示文案
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt;= min 时抛出
     */
    public static <T extends Comparable<? super T>> void gt(T value, T min, String msg) {
        if (value == null || min == null || value.compareTo(min) <= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标值必须严格大于基准值 (value > min)（支持 {} 占位符惰性格式化）
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt;= min 时抛出
     */
    public static <T extends Comparable<? super T>> void gt(T value, T min, String msg, Object... args) {
        if (value == null || min == null || value.compareTo(min) <= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
        }
    }

    /**
     * 断言目标值必须严格大于基准值 (value > min)
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param code  断言失败响应码
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt;= min 时抛出
     */
    public static <T extends Comparable<? super T>> void gt(T value, T min, IResultCode code) {
        if (value == null || min == null || value.compareTo(min) <= 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标值必须严格大于基准值 (value > min)（支持 Supplier 延迟构建错误信息）
     *
     * @param value           目标值
     * @param min             基准最小值
     * @param messageSupplier 错误信息构建函数
     * @param <T>             可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt;= min 时抛出
     */
    public static <T extends Comparable<? super T>> void gt(T value, T min, Supplier<String> messageSupplier) {
        if (value == null || min == null || value.compareTo(min) <= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    /**
     * 断言目标值必须大于或等于基准值 (value >= min)
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param msg   断言失败提示文案
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt; min 时抛出
     */
    public static <T extends Comparable<? super T>> void gte(T value, T min, String msg) {
        if (value == null || min == null || value.compareTo(min) < 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标值必须大于或等于基准值 (value >= min)（支持 {} 占位符惰性格式化）
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt; min 时抛出
     */
    public static <T extends Comparable<? super T>> void gte(T value, T min, String msg, Object... args) {
        if (value == null || min == null || value.compareTo(min) < 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
        }
    }

    /**
     * 断言目标值必须大于或等于基准值 (value >= min)
     *
     * @param value 目标值
     * @param min   基准最小值
     * @param code  断言失败响应码
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt; min 时抛出
     */
    public static <T extends Comparable<? super T>> void gte(T value, T min, IResultCode code) {
        if (value == null || min == null || value.compareTo(min) < 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标值必须大于或等于基准值 (value >= min)（支持 Supplier 延迟构建错误信息）
     *
     * @param value           目标值
     * @param min             基准最小值
     * @param messageSupplier 错误信息构建函数
     * @param <T>             可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &lt; min 时抛出
     */
    public static <T extends Comparable<? super T>> void gte(T value, T min, Supplier<String> messageSupplier) {
        if (value == null || min == null || value.compareTo(min) < 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    /**
     * 断言目标值必须严格小于基准值 (value < max)
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param msg   断言失败提示文案
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt;= max 时抛出
     */
    public static <T extends Comparable<? super T>> void lt(T value, T max, String msg) {
        if (value == null || max == null || value.compareTo(max) >= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标值必须严格小于基准值 (value < max)（支持 {} 占位符惰性格式化）
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt;= max 时抛出
     */
    public static <T extends Comparable<? super T>> void lt(T value, T max, String msg, Object... args) {
        if (value == null || max == null || value.compareTo(max) >= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
        }
    }

    /**
     * 断言目标值必须严格小于基准值 (value < max)
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param code  断言失败响应码
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt;= max 时抛出
     */
    public static <T extends Comparable<? super T>> void lt(T value, T max, IResultCode code) {
        if (value == null || max == null || value.compareTo(max) >= 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标值必须严格小于基准值 (value < max)（支持 Supplier 延迟构建错误信息）
     *
     * @param value           目标值
     * @param max             基准最大值
     * @param messageSupplier 错误信息构建函数
     * @param <T>             可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt;= max 时抛出
     */
    public static <T extends Comparable<? super T>> void lt(T value, T max, Supplier<String> messageSupplier) {
        if (value == null || max == null || value.compareTo(max) >= 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    /**
     * 断言目标值必须小于或等于基准值 (value <= max)
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param msg   断言失败提示文案
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt; max 时抛出
     */
    public static <T extends Comparable<? super T>> void lte(T value, T max, String msg) {
        if (value == null || max == null || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标值必须小于或等于基准值 (value <= max)（支持 {} 占位符惰性格式化）
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt; max 时抛出
     */
    public static <T extends Comparable<? super T>> void lte(T value, T max, String msg, Object... args) {
        if (value == null || max == null || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
        }
    }

    /**
     * 断言目标值必须小于或等于基准值 (value <= max)
     *
     * @param value 目标值
     * @param max   基准最大值
     * @param code  断言失败响应码
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt; max 时抛出
     */
    public static <T extends Comparable<? super T>> void lte(T value, T max, IResultCode code) {
        if (value == null || max == null || value.compareTo(max) > 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标值必须小于或等于基准值 (value <= max)（支持 Supplier 延迟构建错误信息）
     *
     * @param value           目标值
     * @param max             基准最大值
     * @param messageSupplier 错误信息构建函数
     * @param <T>             可比较类型泛型
     * @throws BusinessException 当 value 为 null 或 value &gt; max 时抛出
     */
    public static <T extends Comparable<? super T>> void lte(T value, T max, Supplier<String> messageSupplier) {
        if (value == null || max == null || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    /**
     * 断言目标值必须处于闭区间 [min, max] 内 (min <= value <= max)
     *
     * @param value 目标值
     * @param min   区间最小值
     * @param max   区间最大值
     * @param msg   断言失败提示文案
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当任一参数为 null、min > max 或 value 超出区间时抛出
     */
    public static <T extends Comparable<? super T>> void between(T value, T min, T max, String msg) {
        if (value == null || min == null || max == null || min.compareTo(max) > 0
                || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 断言目标值必须处于闭区间 [min, max] 内 (min <= value <= max)（支持 {} 占位符惰性格式化）
     *
     * @param value 目标值
     * @param min   区间最小值
     * @param max   区间最大值
     * @param msg   断言失败提示文案模板
     * @param args  占位符替换参数
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当任一参数为 null、min > max 或 value 超出区间时抛出
     */
    public static <T extends Comparable<? super T>> void between(T value, T min, T max, String msg, Object... args) {
        if (value == null || min == null || max == null || min.compareTo(max) > 0
                || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), format(msg, args));
        }
    }

    /**
     * 断言目标值必须处于闭区间 [min, max] 内 (min <= value <= max)
     *
     * @param value 目标值
     * @param min   区间最小值
     * @param max   区间最大值
     * @param code  断言失败响应码
     * @param <T>   可比较类型泛型
     * @throws BusinessException 当任一参数为 null、min > max 或 value 超出区间时抛出
     */
    public static <T extends Comparable<? super T>> void between(T value, T min, T max, IResultCode code) {
        if (value == null || min == null || max == null || min.compareTo(max) > 0
                || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(code);
        }
    }

    /**
     * 断言目标值必须处于闭区间 [min, max] 内 (min <= value <= max)（支持 Supplier 延迟构建错误信息）
     *
     * @param value           目标值
     * @param min             区间最小值
     * @param max             区间最大值
     * @param messageSupplier 错误信息构建函数
     * @param <T>             可比较类型泛型
     * @throws BusinessException 当任一参数为 null、min > max 或 value 超出区间时抛出
     */
    public static <T extends Comparable<? super T>> void between(T value, T min, T max, Supplier<String> messageSupplier) {
        if (value == null || min == null || max == null || min.compareTo(max) > 0
                || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), resolveMessage(messageSupplier));
        }
    }

    // ==================== 内部格式化辅助 ====================

    /**
     * 替换模板字符串中的 "{}" 占位符（惰性格式化，统一收敛复用 StringUtil 原生原语）
     *
     * @param template 模板字符串
     * @param args     参数列表
     * @return 格式化后的字符串
     */
    private static String format(String template, Object... args) {
        return StringUtil.format(template, args);
    }

    /**
     * 安全提取 Supplier 返回的错误文本
     *
     * @param supplier 错误文本构建函数
     * @return 构建出的文本，若 supplier 为 null 则返回 null
     */
    private static String resolveMessage(Supplier<String> supplier) {
        return supplier != null ? supplier.get() : null;
    }
}
