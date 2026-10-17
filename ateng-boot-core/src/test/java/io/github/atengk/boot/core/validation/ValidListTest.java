package io.github.atengk.boot.core.validation;

import io.github.atengk.boot.core.util.ValidatorUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ValidList 级联校验与集合包装功能单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("ValidList 级联校验与集合包装单元测试")
class ValidListTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ItemDTO {
        @NotNull(message = "ID 不能为空")
        private Long id;

        @NotBlank(message = "名称不能为空")
        private String name;
    }

    @Test
    @DisplayName("测试集合基础操作与工厂方法")
    void testListOperations() {
        ItemDTO item1 = new ItemDTO(1L, "Apple");
        ItemDTO item2 = new ItemDTO(2L, "Banana");

        ValidList<ItemDTO> list = ValidList.of(item1, item2);
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isEqualTo(item1);
        assertThat(list.get(1)).isEqualTo(item2);
        assertThat(list.isEmpty()).isFalse();
        assertThat(list.contains(item1)).isTrue();

        list.remove(0);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isEqualTo(item2);

        list.clear();
        assertThat(list).isEmpty();
    }

    @Test
    @DisplayName("测试级联 JSR-303 校验触发成功")
    void testCascadeValidationSuccess() {
        ValidList<ItemDTO> validList = ValidList.of(
                new ItemDTO(1L, "Item 1"),
                new ItemDTO(2L, "Item 2")
        );

        // 元素完全合法，不应抛出异常
        ValidatorUtil.validate(validList);
        assertThat(ValidatorUtil.validateResult(validList)).isEmpty();
    }

    @Test
    @DisplayName("测试级联 JSR-303 校验检测到内部元素违规并快速失败")
    void testCascadeValidationFailure() {
        ValidList<ItemDTO> invalidList = ValidList.of(
                new ItemDTO(1L, "Item 1"),
                new ItemDTO(null, "") // 两个违规字段
        );

        // 应当检测到内部 ItemDTO 违规并抛出 BusinessException
        assertThatThrownBy(() -> ValidatorUtil.validate(invalidList))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class);

        assertThat(ValidatorUtil.validateResult(invalidList)).isNotEmpty();
    }

    @Test
    @DisplayName("测试空构造与 null 保护")
    void testConstructorsAndNullSafety() {
        ValidList<String> emptyList = new ValidList<>();
        assertThat(emptyList).isEmpty();

        ValidList<String> fromNull = new ValidList<>(null);
        assertThat(fromNull).isEmpty();

        ValidList<String> ofNull = ValidList.of((List<String>) null);
        assertThat(ofNull).isEmpty();

        assertThat(emptyList.equals(fromNull)).isTrue();
        assertThat(emptyList.hashCode()).isEqualTo(fromNull.hashCode());
        assertThat(emptyList.toString()).isEqualTo("[]");
    }
}
