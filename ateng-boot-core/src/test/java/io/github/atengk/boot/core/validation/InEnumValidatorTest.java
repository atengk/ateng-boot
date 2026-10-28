package io.github.atengk.boot.core.validation;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.ValidatorUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @InEnum 与 InEnumValidator JSR-303 参数校验单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("@InEnum 与 InEnumValidator JSR-303 参数校验单元测试")
class InEnumValidatorTest {

    @Getter
    @RequiredArgsConstructor
    enum StatusEnum implements IBaseEnum<Integer> {
        ACTIVE(1, "启用"),
        DISABLED(2, "禁用");

        private final Integer code;
        private final String label;
    }

    @Getter
    @RequiredArgsConstructor
    enum RoleEnum implements IBaseEnum<String> {
        ADMIN("admin", "管理员"),
        USER("user", "用户");

        private final String code;
        private final String label;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class UserDTO {
        @NotNull(message = "ID不能为空")
        private Long id;

        @InEnum(value = StatusEnum.class, message = "用户状态不合法")
        private Integer status;

        @InEnum(value = RoleEnum.class, message = "角色编码不合法")
        private String role;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StrictStatusDTO {
        @NotNull(message = "状态必填")
        @InEnum(value = StatusEnum.class, message = "状态值无效")
        private Integer status;
    }

    @Test
    @DisplayName("测试入参合法时校验完全通过")
    void testValidationSuccess() {
        UserDTO dto = new UserDTO(1L, 1, "admin");
        Set<ConstraintViolation<UserDTO>> violations = ValidatorUtil.validateResult(dto);
        assertThat(violations).isEmpty();

        // 编程式断言不应抛异常
        ValidatorUtil.validate(dto);
    }

    @Test
    @DisplayName("测试空值放行策略（未标注 @NotNull 时允许为 null）")
    void testNullPassThrough() {
        UserDTO dto = new UserDTO(1L, null, null);
        Set<ConstraintViolation<UserDTO>> violations = ValidatorUtil.validateResult(dto);
        assertThat(violations).isEmpty();
        ValidatorUtil.validate(dto);
    }

    @Test
    @DisplayName("测试配合 @NotNull 时拦截 null 值")
    void testNotNullWithInEnum() {
        StrictStatusDTO dto = new StrictStatusDTO(null);
        Set<ConstraintViolation<StrictStatusDTO>> violations = ValidatorUtil.validateResult(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("状态必填");
    }

    @Test
    @DisplayName("测试非法枚举 code 触发校验失败并抛出 BusinessException")
    void testInvalidEnumCodeFails() {
        UserDTO dto = new UserDTO(1L, 999, "admin");
        Set<ConstraintViolation<UserDTO>> violations = ValidatorUtil.validateResult(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("用户状态不合法");

        assertThatThrownBy(() -> ValidatorUtil.validate(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户状态不合法");
    }

    @Test
    @DisplayName("测试非法 String 枚举 code 触发校验失败")
    void testInvalidStringEnumCodeFails() {
        UserDTO dto = new UserDTO(1L, 1, "super_admin");
        Set<ConstraintViolation<UserDTO>> violations = ValidatorUtil.validateResult(dto);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("角色编码不合法");

        assertThatThrownBy(() -> ValidatorUtil.validate(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色编码不合法");
    }
}
