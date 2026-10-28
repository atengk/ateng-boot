package io.github.atengk.boot.core.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ValidationException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨中间件消息消费毒丸与不可自愈致命异常标准判定器
 * 集中收敛全反应堆（RabbitMQ、Kafka、RocketMQ）对不可自愈异常的判定标准，
 * 凡反序列化失败、JSR-303 参数校验失败、类型转换失败或非法参数等异常，统一判定为 Fatal，
 * 指导消费端拦截器 0 次重试快速送入死信队列或丢弃，坚决阻断无界重试打爆 Broker 与网络
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class FatalExceptionClassifier {

    private static final Set<Class<? extends Throwable>> CUSTOM_FATAL_CLASSES = ConcurrentHashMap.newKeySet();

    /**
     * 知名不可自愈致命异常类名集合 (用于解耦下游可选依赖如 spring-messaging, spring-web 等)
     */
    private static final Set<String> FATAL_CLASS_NAMES = Set.of(
            "org.springframework.messaging.converter.MessageConversionException",
            "org.springframework.http.converter.HttpMessageConversionException",
            "org.springframework.core.convert.ConversionException",
            "org.springframework.web.bind.MethodArgumentNotValidException",
            "org.springframework.validation.BindException",
            "org.springframework.core.codec.DecodingException",
            "org.apache.kafka.common.errors.SerializationException",
            "com.fasterxml.jackson.core.JsonParseException",
            "com.fasterxml.jackson.databind.JsonMappingException",
            "com.fasterxml.jackson.databind.exc.MismatchedInputException"
    );

    private FatalExceptionClassifier() {
    }

    /**
     * 注册自定义致命不可自愈异常类型
     *
     * @param fatalClass 致命异常类
     */
    public static void registerFatal(Class<? extends Throwable> fatalClass) {
        if (fatalClass != null) {
            CUSTOM_FATAL_CLASSES.add(fatalClass);
        }
    }

    /**
     * 判断给定异常是否属于不可自愈的毒丸异常
     * 递归遍历整个 Cause 因果链，只要根因或任何中间因果节点命中毒丸特征即判定为 true
     *
     * @param throwable 待判定异常
     * @return true 表示属于不可自愈异常，应快速失败或进入死信；false 表示可自愈，可安全重试
     */
    public static boolean isFatal(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 避免异常环形引用导致死循环
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        int depth = 0;
        final int maxDepth = 30;

        while (current != null && depth < maxDepth && visited.add(current)) {
            if (isSingleFatal(current)) {
                return true;
            }
            current = current.getCause();
            depth++;
        }

        return false;
    }

    /**
     * 判定单层异常对象是否命中致命毒丸特征
     *
     * @param t 单层异常
     * @return true 表示命中
     */
    private static boolean isSingleFatal(Throwable t) {
        Class<?> clazz = t.getClass();

        // 1. 显式注册的自定义致命异常
        for (Class<? extends Throwable> fatalClass : CUSTOM_FATAL_CLASSES) {
            if (fatalClass.isAssignableFrom(clazz)) {
                return true;
            }
        }

        // 2. 强类型已知类层级判定
        if (t instanceof ValidationException
                || t instanceof JsonProcessingException
                || t instanceof IllegalArgumentException
                || t instanceof IllegalStateException
                || t instanceof ClassCastException
                || t instanceof NullPointerException
                || t instanceof ClassNotFoundException
                || t instanceof NoClassDefFoundError
                || t instanceof SecurityException) {
            return true;
        }

        // 3. 基于类名及其继承树类名匹配 (支持下游中间件专有异常解耦判定)
        Class<?> walk = clazz;
        while (walk != null && walk != Throwable.class && walk != Object.class) {
            String name = walk.getName();
            if (FATAL_CLASS_NAMES.contains(name)) {
                return true;
            }
            if (name.endsWith("ValidationException")
                    || name.endsWith("ConstraintViolationException")
                    || name.endsWith("MessageConversionException")
                    || name.endsWith("SerializationException")) {
                return true;
            }
            walk = walk.getSuperclass();
        }

        return false;
    }

    /**
     * 获取异常链的最深层根因异常 (Root Cause)
     *
     * @param throwable 原始异常
     * @return 最深层根因异常，入参为 null 时返回 null
     */
    public static Throwable getRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current.getCause() != null && visited.add(current)) {
            current = current.getCause();
        }
        return current;
    }
}
