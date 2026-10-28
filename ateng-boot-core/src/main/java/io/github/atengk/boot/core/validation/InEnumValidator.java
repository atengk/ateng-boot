package io.github.atengk.boot.core.validation;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link InEnum} 注解的 JSR-303 约束校验器实现
 * 校验属性值是否属于目标 {@link IBaseEnum} 枚举的有效编码值
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class InEnumValidator implements ConstraintValidator<InEnum, Object> {

    private Class<? extends IBaseEnum<?>> enumClass;

    @Override
    public void initialize(InEnum constraintAnnotation) {
        this.enumClass = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        // 空值放行：若为必填参数，由 @NotNull 或 @NotBlank 负责防御
        if (value == null) {
            return true;
        }
        if (enumClass == null) {
            return false;
        }
        return EnumUtil.isValidCode(enumClass, value);
    }
}
