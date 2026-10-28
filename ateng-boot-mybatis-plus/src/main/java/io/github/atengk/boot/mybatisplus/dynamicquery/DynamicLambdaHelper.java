package io.github.atengk.boot.mybatisplus.dynamicquery;

import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态方法引用反射与元数据推导工具
 * 基于 Java 原生 SerializedLambda 从 Serializable 函数式接口中提取目标方法名与所属类全限定名
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public final class DynamicLambdaHelper {

    private static final Map<Class<?>, String> METHOD_NAME_CACHE = new ConcurrentHashMap<>();

    private DynamicLambdaHelper() {
    }

    /**
     * 从可序列化的函数式接口方法引用中提取方法名
     *
     * @param fn 方法引用实例（如 baseMapper::selectUserDeptPage）
     * @return 方法名（如 "selectUserDeptPage"），提取失败时返回 null
     */
    public static String resolveMethodName(Serializable fn) {
        if (fn == null) {
            return null;
        }
        String cached = METHOD_NAME_CACHE.get(fn.getClass());
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        try {
            Method writeReplace = fn.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(fn);
            String methodName = lambda.getImplMethodName();
            METHOD_NAME_CACHE.put(fn.getClass(), methodName != null ? methodName : "");
            return methodName;
        } catch (Throwable e) {
            log.debug("无法从方法引用中反射提取方法名: {}", e.getMessage());
            METHOD_NAME_CACHE.put(fn.getClass(), "");
            return null;
        }
    }
}
