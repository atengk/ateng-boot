package io.github.atengk.boot.ai.registry;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.atengk.boot.ai.context.AiContextHolder;
import io.github.atengk.boot.ai.exception.AiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * EmbeddingModelRegistry 统一注册中心全维度单测
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("EmbeddingModelRegistry 统一注册中心全维度单测")
class EmbeddingModelRegistryTest {

    private EmbeddingModelRegistry registry;
    private EmbeddingModel defaultModel;
    private EmbeddingModel bgeLargeModel;
    private EmbeddingModel text3SmallModel;

    @BeforeEach
    void setUp() {
        registry = new EmbeddingModelRegistry();
        defaultModel = mock(EmbeddingModel.class);
        bgeLargeModel = mock(EmbeddingModel.class);
        text3SmallModel = mock(EmbeddingModel.class);
        AiContextHolder.clearAll();
    }

    @AfterEach
    void tearDown() {
        AiContextHolder.clearAll();
        registry.clear();
    }

    @Test
    @DisplayName("注册与获取默认向量模型及别名成功")
    void testRegisterDefaultAndAlias() {
        registry.registerDefault(defaultModel, "text-embedding-3-small");

        assertThat(registry.getDefaultModel()).isSameAs(defaultModel);
        assertThat(registry.getModel(EmbeddingModelRegistry.DEFAULT_MODEL_NAME)).isSameAs(defaultModel);
        assertThat(registry.getModel("text-embedding-3-small")).isSameAs(defaultModel);
        assertThat(registry.hasModel("text-embedding-3-small")).isTrue();
        assertThat(registry.size()).isEqualTo(2); // "default" + "text-embedding-3-small"
    }

    @Test
    @DisplayName("注册多个命名模型并正确隔离检索")
    void testRegisterMultipleNamedModels() {
        registry.register("bge-large", bgeLargeModel);
        registry.register("text-3-small", text3SmallModel);

        assertThat(registry.hasModel("bge-large")).isTrue();
        assertThat(registry.hasModel("text-3-small")).isTrue();
        assertThat(registry.hasModel("non-existent")).isFalse();

        assertThat(registry.getModel("bge-large")).isSameAs(bgeLargeModel);
        assertThat(registry.getModel("text-3-small")).isSameAs(text3SmallModel);
        assertThat(registry.getModel("non-existent")).isNull();

        assertThat(registry.listModels()).containsExactlyInAnyOrder("bge-large", "text-3-small");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("模型名称为空或空白字符时防御性抛出 AiException")
    void testRegisterWithBlankNameThrowsException() {
        assertThatThrownBy(() -> registry.register(null, bgeLargeModel))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("注册 Embedding 模型名称不能为空");

        assertThatThrownBy(() -> registry.register("   ", bgeLargeModel))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("注册 Embedding 模型名称不能为空");
    }

    @Test
    @DisplayName("根据 AiContextHolder 线程上下文获取当前生效向量模型")
    void testGetEffectiveModelWithContextHolder() {
        registry.registerDefault(defaultModel, "default-alias");
        registry.register("bge-large", bgeLargeModel);

        // 1. 无上下文时返回默认模型
        assertThat(registry.getEffectiveModel()).isSameAs(defaultModel);

        // 2. 压入上下文后生效目标模型
        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useEmbeddingModel("bge-large")) {
            assertThat(registry.getEffectiveModel()).isSameAs(bgeLargeModel);
        }

        // 3. 退出作用域后自动还原为默认模型
        assertThat(registry.getEffectiveModel()).isSameAs(defaultModel);
    }

    @Test
    @DisplayName("上下文指定未知模型别名时平滑回退默认模型")
    void testGetEffectiveModelFallbackWhenUnknown() {
        registry.registerDefault(defaultModel, null);

        try (AiContextHolder.AiModelScope ignored = AiContextHolder.useEmbeddingModel("unknown-model")) {
            assertThat(registry.getEffectiveModel()).isSameAs(defaultModel);
        }
    }

    @Test
    @DisplayName("清空注册中心后状态与大小正确清零")
    void testClearRegistry() {
        registry.register("bge-large", bgeLargeModel);
        assertThat(registry.size()).isEqualTo(1);

        registry.clear();
        assertThat(registry.size()).isZero();
        assertThat(registry.getModel("bge-large")).isNull();
        assertThat(registry.listModels()).isEmpty();
    }
}
