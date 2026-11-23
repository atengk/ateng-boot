package io.github.atengk.boot.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 枚举对外暴露字典别名注解
 * 用于显式指定或重写枚举对外暴露的字典标识，防御跨模块同名枚举冲突并支持语义化别名定制
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnumAlias {

    /**
     * 对外暴露的字典别名（如 userStatus、orderType 等）
     *
     * @return 字典别名
     */
    String value();

    /**
     * 字典名称或业务描述说明（可选）
     *
     * @return 字典说明
     */
    String description() default "";
}
