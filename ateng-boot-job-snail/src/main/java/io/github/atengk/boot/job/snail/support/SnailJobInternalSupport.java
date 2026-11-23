package io.github.atengk.boot.job.snail.support;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder.TenantSnapshot;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code ateng-boot-job-snail} 内部共享辅助工具
 *
 * <p>集中收口受管线程池解析、四维与调度上下文异步透传包装、注解名称解析及基本类型默认值查表，
 * 避免模板类、切面、扫描器与测试助手之间出现重复逻辑或异常静默吞咽。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class SnailJobInternalSupport {

    private static final Logger log = LoggerFactory.getLogger(SnailJobInternalSupport.class);

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
            boolean.class, Boolean.FALSE,
            byte.class, (byte) 0,
            short.class, (short) 0,
            int.class, 0,
            long.class, 0L,
            float.class, 0.0f,
            double.class, 0.0d,
            char.class, '\0'
    );

    private SnailJobInternalSupport() {
    }

    /**
     * 解析当前生效的受管线程池执行器
     *
     * <p>当 {@code managedPoolEnabled} 为 {@code false} 时直接返回 {@code null}（禁止从 Spring 回捞），
     * 保障关闭开关可确定退回调用线程或同步执行。</p>
     *
     * @param explicitExecutor   显式注入的执行器
     * @param managedPoolEnabled 是否允许启用 Core 受管线程池
     * @return 受管线程池实例；禁用或容器未注册时返回 {@code null}
     */
    public static Executor resolveManagedExecutor(Executor explicitExecutor, boolean managedPoolEnabled) {
        if (!managedPoolEnabled) {
            return null;
        }
        if (explicitExecutor != null) {
            return explicitExecutor;
        }
        try {
            return SpringUtil.getBean("taskExecutor", Executor.class);
        } catch (BeansException | IllegalStateException ex) {
            log.debug("未检测到 Core 受管线程池 [taskExecutor]，退化为当前线程执行: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 统一解析 {@link AtengSnailJob} 注解声明的任务名称（{@code name} 与 {@code value} 互为别名）
     *
     * @param annotation   方法或类级注解实例
     * @param fallbackName 未显式指定名称时的回退名
     * @return 解析后的任务名称
     */
    public static String resolveStandaloneJobName(AtengSnailJob annotation, String fallbackName) {
        if (annotation != null) {
            if (StringUtils.hasText(annotation.name())) {
                return annotation.name().trim();
            }
            if (StringUtils.hasText(annotation.value())) {
                return annotation.value().trim();
            }
        }
        return fallbackName != null ? fallbackName.trim() : "";
    }

    /**
     * 查找 Java 基本类型对应的零值包装对象（非基本类型返回 {@code null}）
     *
     * @param primitiveType 参数类型
     * @return 基本类型默认值或 {@code null}
     */
    public static Object defaultPrimitiveValue(Class<?> primitiveType) {
        return primitiveType != null ? PRIMITIVE_DEFAULTS.get(primitiveType) : null;
    }

    /**
     * 为异步动作捕获并在工作线程绑定与还原 MDC、User、Tenant 与 {@link SnailJobContext}
     *
     * @param supplier 异步执行的业务动作
     * @param <T>      返回值类型
     * @return 携带上下文快照透传能力的新 {@link Supplier}
     */
    public static <T> Supplier<T> wrapWithContext(Supplier<T> supplier) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        String capturedUser = UserContextHolder.getExplicit();
        TenantSnapshot capturedTenant = TenantContextHolder.capture();
        SnailJobContext capturedJobContext = SnailJobContextHolder.get();

        return () -> {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            String prevUser = UserContextHolder.getExplicit();
            TenantSnapshot prevTenant = TenantContextHolder.capturePrevious();
            SnailJobContext prevJobContext = SnailJobContextHolder.get();
            try {
                restoreMdc(capturedMdc);
                restoreUser(capturedUser);
                TenantContextHolder.restore(capturedTenant);
                SnailJobContextHolder.set(capturedJobContext);
                return supplier.get();
            } finally {
                SnailJobContextHolder.set(prevJobContext);
                TenantContextHolder.restore(prevTenant);
                restoreUser(prevUser);
                restoreMdc(prevMdc);
            }
        };
    }

    private static void restoreMdc(Map<String, String> contextMap) {
        if (contextMap != null) {
            MDC.setContextMap(contextMap);
        } else {
            MDC.clear();
        }
    }

    private static void restoreUser(String user) {
        if (user != null) {
            UserContextHolder.set(user);
        } else {
            UserContextHolder.clear();
        }
    }
}
