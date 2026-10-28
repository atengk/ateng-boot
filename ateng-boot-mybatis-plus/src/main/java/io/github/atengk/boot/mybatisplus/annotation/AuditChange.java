package io.github.atengk.boot.mybatisplus.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据变更 Diff 审计注解
 * 声明于实体类或 Mapper 接口/方法上，触发数据变更前置快照捕获与字段级 Diff 增量比对
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AuditChange {

    /**
     * 是否开启变更审计（默认 true）
     *
     * @return 是否启用
     */
    boolean enabled() default true;

    /**
     * 需纳入审计比对的目标字段集合（默认空数组，表示纳入全部有效业务属性）
     *
     * @return 目标属性列表
     */
    String[] includeFields() default {};

    /**
     * 需排除在外的审计字段集合（如 update_time、update_by 或敏感凭据字段）
     *
     * @return 排除属性列表
     */
    String[] excludeFields() default {};

    /**
     * 变更业务说明或所属业务模块标识
     *
     * @return 业务描述
     */
    String description() default "";
}
