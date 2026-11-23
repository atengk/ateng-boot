package io.github.atengk.boot.ai;

import io.github.atengk.boot.ai.context.AiContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 上下文路由器单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class AiContextHolderTest {

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
    }

    @Test
    @DisplayName("初始状态下模型上下文应为空")
    void testInitialEmpty() {
        assertThat(AiContextHolder.getModelName()).isNull();
    }

    @Test
    @DisplayName("验证单模型压栈、读取与出栈清理")
    void testSetAndClear() {
        AiContextHolder.setModelName("deepseek-chat");
        assertThat(AiContextHolder.getModelName()).isEqualTo("deepseek-chat");

        AiContextHolder.clear();
        assertThat(AiContextHolder.getModelName()).isNull();
    }

    @Test
    @DisplayName("验证多层嵌套模型调用压栈与先进后出弹出")
    void testNestedStackBehavior() {
        AiContextHolder.setModelName("model-a");
        assertThat(AiContextHolder.getModelName()).isEqualTo("model-a");

        AiContextHolder.setModelName("model-b");
        assertThat(AiContextHolder.getModelName()).isEqualTo("model-b");

        AiContextHolder.clear();
        assertThat(AiContextHolder.getModelName()).isEqualTo("model-a");

        AiContextHolder.clear();
        assertThat(AiContextHolder.getModelName()).isNull();
    }

    @Test
    @DisplayName("验证 try-with-resources 自动释放作用域")
    void testUseModelScope() {
        AiContextHolder.setModelName("outer-model");

        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel("inner-model")) {
            assertThat(AiContextHolder.getModelName()).isEqualTo("inner-model");
        }

        assertThat(AiContextHolder.getModelName()).isEqualTo("outer-model");
    }

    @Test
    @DisplayName("防御性测试：空白或 null 模型名称不应压入栈中")
    void testBlankAndNullIgnored() {
        AiContextHolder.setModelName(null);
        assertThat(AiContextHolder.getModelName()).isNull();

        AiContextHolder.setModelName("   ");
        assertThat(AiContextHolder.getModelName()).isNull();
    }

    @Test
    @DisplayName("防御性测试：useModel 传入 null 或空白时不应破坏外层已有上下文")
    void testUseModelWithBlankOrNullPreservesOuter() {
        AiContextHolder.setModelName("outer-model");
        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel(null)) {
            assertThat(AiContextHolder.getModelName()).isEqualTo("outer-model");
        }
        assertThat(AiContextHolder.getModelName()).isEqualTo("outer-model");

        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel("   ")) {
            assertThat(AiContextHolder.getModelName()).isEqualTo("outer-model");
        }
        assertThat(AiContextHolder.getModelName()).isEqualTo("outer-model");
    }

    @Test
    @DisplayName("验证 clearAll 彻底清空所有嵌套层级")
    void testClearAll() {
        AiContextHolder.setModelName("model-1");
        AiContextHolder.setModelName("model-2");
        AiContextHolder.setModelName("model-3");

        AiContextHolder.clearAll();
        assertThat(AiContextHolder.getModelName()).isNull();
    }
}
