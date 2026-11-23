package io.github.atengk.boot.mail.properties;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

/**
 * 强类型 {@link Duration} 配置合法性约束注解
 * 默认要求大于 0；可通过 {@link #allowZero()} 允许零值但不允许负数。
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Documented
@Constraint(validatedBy = ValidDuration.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDuration {

    String message() default "时长配置必须为正数";

    boolean allowZero() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * {@link Duration} 校验器实现
     */
    class Validator implements ConstraintValidator<ValidDuration, Duration> {

        private boolean allowZero;

        @Override
        public void initialize(ValidDuration constraintAnnotation) {
            this.allowZero = constraintAnnotation.allowZero();
        }

        @Override
        public boolean isValid(Duration value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.isNegative()) {
                return false;
            }
            return allowZero || !value.isZero();
        }
    }
}
