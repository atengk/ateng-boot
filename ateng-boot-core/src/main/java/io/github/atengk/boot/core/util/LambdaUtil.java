package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Lambda 表达式与属性方法引用解析工具
 * 纯 JDK 17 原生实现，基于 SerializedLambda 机制推导强类型实体字段名，支持本地有界并发缓存
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class LambdaUtil {

    private static final int DEFAULT_CACHE_CAPACITY = 1024;
    private static final ConcurrentLruCache<Class<?>, SerializedLambda> LAMBDA_CACHE = new ConcurrentLruCache<>(DEFAULT_CACHE_CAPACITY);
    private static final ConcurrentLruCache<String, String> FIELD_NAME_CACHE = new ConcurrentLruCache<>(DEFAULT_CACHE_CAPACITY);

    private LambdaUtil() {
    }

    /**
     * 基于方法引用解析实体字段名（如 User::getName -> "name"，User::isDeleted -> "deleted"）
     *
     * @param func 属性提取函数
     * @param <T>  实体类型
     * @param <R>  属性类型
     * @return 实体属性字段名
     */
    public static <T, R> String getFieldName(PropertyFunc<T, R> func) {
        Objects.requireNonNull(func, "func 函数不能为空");
        SerializedLambda lambda = resolve(func);
        String implMethodName = lambda.getImplMethodName();
        String implClass = lambda.getImplClass();
        String cacheKey = implClass + "#" + implMethodName;

        return FIELD_NAME_CACHE.computeIfAbsent(cacheKey, k -> methodToProperty(implMethodName));
    }

    /**
     * 解析 PropertyFunc 为 SerializedLambda 元数据
     *
     * @param func 属性提取函数
     * @return SerializedLambda 实例
     */
    public static SerializedLambda resolve(PropertyFunc<?, ?> func) {
        Objects.requireNonNull(func, "func 函数不能为空");
        Class<?> clazz = func.getClass();
        return LAMBDA_CACHE.computeIfAbsent(clazz, k -> {
            try {
                Method method = clazz.getDeclaredMethod("writeReplace");
                method.setAccessible(true);
                return (SerializedLambda) method.invoke(func);
            } catch (Exception e) {
                throw new IllegalArgumentException("无法解析 Lambda 表达式方法引用，请确保传入合法的实体方法引用", e);
            }
        });
    }

    /**
     * 将 getter / is 方法名转换为 JavaBean 属性名
     * 遵循标准 JavaBean 规范且无需依赖 java.desktop 模块
     *
     * @param methodName 方法名
     * @return 属性名
     */
    public static String methodToProperty(String methodName) {
        Objects.requireNonNull(methodName, "methodName 不能为空");
        String name = methodName;
        if (name.startsWith("is") && name.length() > 2) {
            name = name.substring(2);
        } else if (name.startsWith("get") && name.length() > 3) {
            name = name.substring(3);
        } else if (name.startsWith("set") && name.length() > 3) {
            name = name.substring(3);
        }

        return decapitalize(name);
    }

    /**
     * 字符串首字母小写转换算法（标准 JavaBean 命名规范实现）
     * 若字符串为空或前两位均为大写字母（如 "URL", "ID"），保持原样；否则将首字母转为小写
     *
     * @param name 待转换字符串
     * @return 转换后的属性名
     */
    private static String decapitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * 清空当前所有缓存的 Lambda 与字段名元数据
     */
    public static void clearCache() {
        LAMBDA_CACHE.clear();
        FIELD_NAME_CACHE.clear();
    }

    /**
     * 获取当前字段名缓存条目数
     *
     * @return 当前缓存大小
     */
    public static int getCacheSize() {
        return FIELD_NAME_CACHE.size();
    }
}
