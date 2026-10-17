package io.github.atengk.boot.storage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式多存储源切换注解
 * 支持标注在类或方法上，方法级注解优先级高于类级注解；
 * 由 {@code StorageAspect} 拦截并自动设置当前线程的 {@code StorageContextHolder}，并在执行完毕后严格恢复现场
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface Storage {

    /**
     * 目标存储源名称（对应配置中的 sources 键名，或 "primary"）
     *
     * @return 存储源名称
     */
    String value();
}
