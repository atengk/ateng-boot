package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lambda 表达式与属性方法引用解析工具
 * 纯 JDK 17 原生实现，基于 SerializedLambda 机制推导强类型实体字段名，支持本地有界并发缓存
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class LambdaUtil {

    private static final int DEFAULT_CACHE_CAPACITY = 1024;
    private static final ReentrantLock CACHE_CONFIG_LOCK = new ReentrantLock();
    private static volatile ConcurrentLruCache<Class<?>, SerializedLambda> lambdaCache = new ConcurrentLruCache<>(DEFAULT_CACHE_CAPACITY);
    private static volatile ConcurrentLruCache<String, String> fieldNameCache = new ConcurrentLruCache<>(DEFAULT_CACHE_CAPACITY);

    private LambdaUtil() {
    }

    /**
     * 重新配置 Lambda 与字段映射 LRU 缓存规格（支持容量上限与 TTL 存活时间）
     * 前置校验容量与 TTL 合法性，并线程安全重建底层缓存
     *
     * @param maxCapacity 最大缓存条目容量（必须大于 0）
     * @param ttl         缓存条目存活时间（可为 null 或 Duration.ZERO 表示永不过期，不能为负数）
     * @throws IllegalArgumentException 当 maxCapacity <= 0 或 ttl 为负数时抛出
     */
    public static void configureCache(int maxCapacity, Duration ttl) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity 必须大于 0: " + maxCapacity);
        }
        if (ttl != null && ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 不能为负数: " + ttl);
        }
        CACHE_CONFIG_LOCK.lock();
        try {
            lambdaCache = new ConcurrentLruCache<>(maxCapacity, ttl);
            fieldNameCache = new ConcurrentLruCache<>(maxCapacity, ttl);
        } finally {
            if (CACHE_CONFIG_LOCK.isHeldByCurrentThread()) {
                CACHE_CONFIG_LOCK.unlock();
            }
        }
    }

    /**
     * 获取当前 Lambda 解析缓存条目数
     *
     * @return 缓存条目数
     */
    public static int getLambdaCacheSize() {
        return lambdaCache.size();
    }

    /**
     * 获取当前实体属性字段名映射缓存条目数
     *
     * @return 缓存条目数
     */
    public static int getFieldNameCacheSize() {
        return fieldNameCache.size();
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

        return fieldNameCache.computeIfAbsent(cacheKey, k -> methodToProperty(implMethodName));
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
        return lambdaCache.computeIfAbsent(clazz, k -> {
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
        CACHE_CONFIG_LOCK.lock();
        try {
            lambdaCache.clear();
            fieldNameCache.clear();
        } finally {
            if (CACHE_CONFIG_LOCK.isHeldByCurrentThread()) {
                CACHE_CONFIG_LOCK.unlock();
            }
        }
    }

    /**
     * 获取当前字段名缓存条目数
     *
     * @return 当前缓存大小
     */
    public static int getCacheSize() {
        return fieldNameCache.size();
    }
}
