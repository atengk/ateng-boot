package io.github.atengk.boot.mail.properties;

import io.github.atengk.boot.core.properties.AsyncPoolProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 强类型受管异步线程池配置 {@link AsyncPoolProperties} 边界约束注解
 * 校验核心线程数、最大线程数、队列容量与非负时长边界。
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Documented
@Constraint(validatedBy = ValidAsyncPool.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAsyncPool {

    String message() default "异步线程池配置参数超出合法边界";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * {@link AsyncPoolProperties} 边界校验器实现
     */
    class Validator implements ConstraintValidator<ValidAsyncPool, AsyncPoolProperties> {

        @Override
        public boolean isValid(AsyncPoolProperties value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.getCorePoolSize() < 1 || value.getMaxPoolSize() < 1 || value.getQueueCapacity() < 0) {
                return false;
            }
            if (value.getKeepAlive() != null && value.getKeepAlive().isNegative()) {
                return false;
            }
            return value.getAwaitTermination() == null || !value.getAwaitTermination().isNegative();
        }
    }
}
