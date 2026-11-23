package io.github.atengk.boot.core.annotation;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.validation.InEnumValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验目标属性值是否属于合法枚举 code 的 JSR-303 约束注解
 * 仅用于验证入参值是否符合指定 IBaseEnum 的有效 code 列表，支持空值安全放行（若必填请配合 @NotNull 使用）
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = InEnumValidator.class)
public @interface InEnum {

    /**
     * 绑定的目标枚举类（必须实现 IBaseEnum 接口）
     *
     * @return 枚举类 Class
     */
    Class<? extends IBaseEnum<?>> value();

    /**
     * 校验失败时的错误提示信息
     *
     * @return 错误消息
     */
    String message() default "枚举数值不合法";

    /**
     * 校验分组
     *
     * @return 分组类数组
     */
    Class<?>[] groups() default {};

    /**
     * 负载信息
     *
     * @return Payload 数组
     */
    Class<? extends Payload>[] payload() default {};
}
