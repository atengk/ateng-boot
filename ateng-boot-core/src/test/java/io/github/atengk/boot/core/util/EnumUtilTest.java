package io.github.atengk.boot.core.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.OptionItem;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EnumUtil 与 IBaseEnum 通用枚举契约单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("EnumUtil 与 IBaseEnum 通用枚举契约单元测试")
class EnumUtilTest {

    @Getter
    @RequiredArgsConstructor
    enum StatusEnum implements IBaseEnum<Integer> {
        ACTIVE(1, "正常启用"),
        DISABLED(2, "已禁用") {
            @Override
            public Boolean getDisabled() {
                return true;
            }

            @Override
            public Map<String, Object> getExtra() {
                return Map.of("color", "red");
            }
        },
        PENDING(3, "审核中");

        private final Integer code;
        private final String label;
    }

    @Getter
    @RequiredArgsConstructor
    enum RoleEnum implements IBaseEnum<String> {
        ADMIN("admin", "超级管理员"),
        USER("user", "普通用户");

        private final String code;
        private final String label;
    }

    enum NormalEnum {
        ONE, TWO
    }

    @AfterEach
    void tearDown() {
        EnumUtil.clearCache();
    }

    @Test
    @DisplayName("测试 fromCode 匹配与空安全")
    void testFromCode() {
        // 1. 正常匹配
        Optional<StatusEnum> activeOpt = EnumUtil.fromCode(StatusEnum.class, 1);
        assertThat(activeOpt).isPresent().contains(StatusEnum.ACTIVE);

        Optional<RoleEnum> adminOpt = EnumUtil.fromCode(RoleEnum.class, "admin");
        assertThat(adminOpt).isPresent().contains(RoleEnum.ADMIN);

        // 2. 未匹配
        assertThat(EnumUtil.fromCode(StatusEnum.class, 999)).isEmpty();
        assertThat(EnumUtil.fromCode(RoleEnum.class, "unknown")).isEmpty();

        // 3. 空安全防卫
        assertThat(EnumUtil.<StatusEnum, Integer>fromCode(null, 1)).isEmpty();
        assertThat(EnumUtil.<StatusEnum, Integer>fromCode(StatusEnum.class, null)).isEmpty();
        assertThat(EnumUtil.<StatusEnum, Integer>fromCode(null, null)).isEmpty();
    }

    @Test
    @DisplayName("测试 getByCodeOrThrow 各种重载")
    void testGetByCodeOrThrow() {
        // 1. 正常获取
        StatusEnum status = EnumUtil.getByCodeOrThrow(StatusEnum.class, 1);
        assertThat(status).isEqualTo(StatusEnum.ACTIVE);

        // 2. 默认异常抛出
        assertThatThrownBy(() -> EnumUtil.getByCodeOrThrow(StatusEnum.class, 999))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未找到编码为 [999] 的枚举常量 [StatusEnum]");

        // 3. 自定义错误提示
        assertThatThrownBy(() -> EnumUtil.getByCodeOrThrow(StatusEnum.class, 999, "状态码无效"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("状态码无效");

        // 4. 自定义错误码契约
        assertThatThrownBy(() -> EnumUtil.getByCodeOrThrow(StatusEnum.class, 999, CommonResultCode.BAD_REQUEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("测试 getLabelByCode 标签获取与保底值")
    void testGetLabelByCode() {
        assertThat(EnumUtil.getLabelByCode(StatusEnum.class, 1)).isEqualTo("正常启用");
        assertThat(EnumUtil.getLabelByCode(StatusEnum.class, 999)).isNull();
        assertThat(EnumUtil.getLabelByCode(StatusEnum.class, 999, "未知状态")).isEqualTo("未知状态");
        assertThat(EnumUtil.getLabelByCode(null, 1, "保底")).isEqualTo("保底");
    }

    @Test
    @DisplayName("测试 isValidCode 校验有效性")
    void testIsValidCode() {
        // 强类型版本
        assertThat(EnumUtil.isValidCode(StatusEnum.class, 1)).isTrue();
        assertThat(EnumUtil.isValidCode(StatusEnum.class, 2)).isTrue();
        assertThat(EnumUtil.isValidCode(StatusEnum.class, 999)).isFalse();
        assertThat(EnumUtil.isValidCode(StatusEnum.class, (Integer) null)).isFalse();

        // Object 通用重载版本（支持类型泛化与字符串兼容）
        assertThat(EnumUtil.isValidCode(StatusEnum.class, (Object) 1)).isTrue();
        assertThat(EnumUtil.isValidCode(StatusEnum.class, (Object) "1")).isTrue();
        assertThat(EnumUtil.isValidCode(StatusEnum.class, (Object) "999")).isFalse();

        // 非法 Class 防御
        assertThat(EnumUtil.isValidCode(null, 1)).isFalse();
        assertThat(EnumUtil.isValidCode(NormalEnum.class, "ONE")).isFalse();
        assertThat(EnumUtil.isValidCode(String.class, "test")).isFalse();
    }

    @Test
    @DisplayName("测试 toOptions 选项模型转换")
    void testToOptions() {
        List<OptionItem<Integer>> options = EnumUtil.toOptions(StatusEnum.class);
        assertThat(options).hasSize(3);

        OptionItem<Integer> activeOption = options.get(0);
        assertThat(activeOption.getCode()).isEqualTo(1);
        assertThat(activeOption.getLabel()).isEqualTo("正常启用");
        assertThat(activeOption.getDisabled()).isFalse();
        assertThat(activeOption.getExtra()).isEmpty();

        OptionItem<Integer> disabledOption = options.get(1);
        assertThat(disabledOption.getCode()).isEqualTo(2);
        assertThat(disabledOption.getLabel()).isEqualTo("已禁用");
        assertThat(disabledOption.getDisabled()).isTrue();
        assertThat(disabledOption.getExtra()).containsEntry("color", "red");

        // 空安全：非法类返回空列表
        assertThat(EnumUtil.toOptions(null)).isEmpty();

        // 通用无泛型版本
        List<OptionItem<Object>> untypedOptions = EnumUtil.toOptionsUntyped(StatusEnum.class);
        assertThat(untypedOptions).hasSize(3);
        assertThat(EnumUtil.toOptionsUntyped(null)).isEmpty();
        assertThat(EnumUtil.toOptionsUntyped(NormalEnum.class)).isEmpty();
    }

    @Test
    @DisplayName("测试 getAllCodes 提取全部有效编码")
    void testGetAllCodes() {
        List<Integer> codes = EnumUtil.getAllCodes(StatusEnum.class);
        assertThat(codes).containsExactly(1, 2, 3);

        List<String> roleCodes = EnumUtil.getAllCodes(RoleEnum.class);
        assertThat(roleCodes).containsExactly("admin", "user");

        assertThat(EnumUtil.getAllCodes(null)).isEmpty();
    }

    @Test
    @DisplayName("测试 IBaseEnum 默认方法实现")
    void testIBaseEnumDefaults() {
        StatusEnum active = StatusEnum.ACTIVE;

        // 1. matches
        assertThat(active.matches(1)).isTrue();
        assertThat(active.matches(2)).isFalse();
        assertThat(active.matches(null)).isFalse();

        // 2. getValue 兼容性
        assertThat(active.getValue()).isEqualTo(1);

        // 3. toOption 实例方法
        OptionItem<Integer> option = active.toOption();
        assertThat(option.getCode()).isEqualTo(1);
        assertThat(option.getLabel()).isEqualTo("正常启用");
        assertThat(option.getDisabled()).isFalse();
        assertThat(option.getExtra()).isEmpty();
    }

    @Test
    @DisplayName("测试 OptionItem 静态工厂与模型能力")
    void testOptionItemFactoryAndModel() {
        OptionItem<String> item1 = OptionItem.of("code1", "label1");
        assertThat(item1.getCode()).isEqualTo("code1");
        assertThat(item1.getLabel()).isEqualTo("label1");
        assertThat(item1.getDisabled()).isFalse();
        assertThat(item1.getExtra()).isEmpty();

        OptionItem<String> item2 = OptionItem.of("code2", "label2", true);
        assertThat(item2.getDisabled()).isTrue();

        OptionItem<String> item3 = OptionItem.of("code3", "label3", false, Map.of("key", "val"));
        assertThat(item3.getExtra()).containsEntry("key", "val");

        // null 防卫
        OptionItem<String> item4 = OptionItem.of("code4", "label4", null, null);
        assertThat(item4.getDisabled()).isFalse();
        assertThat(item4.getExtra()).isEmpty();

        // 全参及 builder 测试
        OptionItem<Long> built = OptionItem.<Long>builder()
                .code(100L)
                .label("test")
                .disabled(true)
                .extra(Collections.singletonMap("k", "v"))
                .build();
        assertThat(built.getCode()).isEqualTo(100L);
        assertThat(built.toString()).contains("100");
    }
}
