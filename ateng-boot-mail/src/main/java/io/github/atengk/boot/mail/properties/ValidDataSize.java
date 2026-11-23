package io.github.atengk.boot.mail.properties;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.springframework.util.unit.DataSize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 强类型 {@link DataSize} 容量合法性约束注解（严格要求字节数大于 0）
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Documented
@Constraint(validatedBy = ValidDataSize.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDataSize {

    String message() default "容量配置必须大于 0 字节";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * {@link DataSize} 校验器实现
     */
    class Validator implements ConstraintValidator<ValidDataSize, DataSize> {

        @Override
        public boolean isValid(DataSize value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            return value.toBytes() > 0;
        }
    }
}
