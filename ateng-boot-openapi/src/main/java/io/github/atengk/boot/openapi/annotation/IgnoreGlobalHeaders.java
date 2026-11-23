package io.github.atengk.boot.openapi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 忽略全局请求头自动注入注解
 * <p>
 * 声明在 Controller 类或具体方法上，指示 {@code GlobalHeaderCustomizer} 跳过全局请求头的自动注入：
 * <ul>
 *     <li>若 {@link #value()} 为空数组（默认值），则全量忽略所有全局请求头（包含追踪 ID、租户 ID 与自定义请求头）；</li>
 *     <li>若 {@link #value()} 指定了具体的请求头名称（不区分大小写，如 {@code {"X-Tenant-Id"}}），则仅精细化忽略指定的请求头，其余全局请求头正常保留注入。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface IgnoreGlobalHeaders {

    /**
     * 需要豁免忽略的全局请求头名称列表（不区分大小写，例如 {@code {"X-Tenant-Id", "X-Trace-Id"}}）
     * <p>默认空数组，表示全量忽略所有全局请求头</p>
     *
     * @return 待豁免的请求头名称列表
     */
    String[] value() default {};
}
