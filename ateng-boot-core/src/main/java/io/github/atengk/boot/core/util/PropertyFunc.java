package io.github.atengk.boot.core.util;

import java.io.Serializable;
import java.util.function.Function;

/**
 * 可序列化的属性提取函数接口
 * 用于在 LambdaUtil 中通过方法引用反射推导 JavaBean 字段名
 *
 * @param <T> 入参实体类型
 * @param <R> 返回属性类型
 * @author Ateng
 * @since 2026-10-17
 */
@FunctionalInterface
public interface PropertyFunc<T, R> extends Function<T, R>, Serializable {
}
