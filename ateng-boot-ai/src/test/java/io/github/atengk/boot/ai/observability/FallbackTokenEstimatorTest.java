package io.github.atengk.boot.ai.observability;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式保底 Token 估算器单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class FallbackTokenEstimatorTest {

    @Test
    @DisplayName("测试空值与空白文本估算返回 0")
    void testEstimateNullAndBlank() {
        assertThat(FallbackTokenEstimator.estimateTokens(null)).isEqualTo(0);
        assertThat(FallbackTokenEstimator.estimateTokens("")).isEqualTo(0);
        assertThat(FallbackTokenEstimator.estimateTokens("   ")).isEqualTo(0);
        assertThat(FallbackTokenEstimator.estimateTokens("\t\r\n")).isEqualTo(0);
    }

    @Test
    @DisplayName("测试纯中文文本启发式估算 (字数 * 1.5)")
    void testEstimatePureChinese() {
        // 4 个汉字 -> 4 * 1.5 = 6.0 -> 6
        assertThat(FallbackTokenEstimator.estimateTokens("你好世界")).isEqualTo(6);

        // 7 个汉字 -> 7 * 1.5 = 10.5 -> 11
        assertThat(FallbackTokenEstimator.estimateTokens("人工智能大模型")).isEqualTo(11);
    }

    @Test
    @DisplayName("测试纯英文与数字文本启发式估算 (词数 * 1.3)")
    void testEstimatePureEnglish() {
        // 2 个词 -> 2 * 1.3 = 2.6 -> 3
        assertThat(FallbackTokenEstimator.estimateTokens("Hello world")).isEqualTo(3);

        // 8 个词 -> 8 * 1.3 = 10.4 -> 10
        assertThat(FallbackTokenEstimator.estimateTokens("This is a unit test for token estimation")).isEqualTo(10);
    }

    @Test
    @DisplayName("测试中英文与数字混合文本估算")
    void testEstimateMixed() {
        // 2 个汉字 (3.0) + 1 个英文词 (1.3) = 4.3 -> 4
        assertThat(FallbackTokenEstimator.estimateTokens("你好 world")).isEqualTo(4);

        // 4 个汉字 (6.0) + 5 个英文/数字词 ("Spring", "Boot", "3", "3", "13" -> 6.5) = 12.5 -> 13
        assertThat(FallbackTokenEstimator.estimateTokens("Spring Boot 3.3.13 框架开发")).isEqualTo(13);
    }

    @Test
    @DisplayName("测试纯标点符号非空文本保底至少计为 1 Token")
    void testEstimatePunctuationAndSymbols() {
        assertThat(FallbackTokenEstimator.estimateTokens("!??#")).isEqualTo(1);
        assertThat(FallbackTokenEstimator.estimateTokens("，。？！")).isEqualTo(1);
    }

    @Test
    @DisplayName("测试根据 prompt 与 response 完整生成 TokenUsage 实例")
    void testEstimateTokenUsage() {
        TokenUsage usage = FallbackTokenEstimator.estimate("你好世界", "Hello world");
        assertThat(usage).isNotNull();
        assertThat(usage.inputTokenCount()).isEqualTo(6);
        assertThat(usage.outputTokenCount()).isEqualTo(3);
        assertThat(usage.totalTokenCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("测试部分 Token 缺失时的增量混合保底估算")
    void testEstimatePartialUsage() {
        // 已有真实 promptTokens=100，completion 缺失 -> 仅估算 completion ("Hello world" -> 3)
        TokenUsage usage1 = FallbackTokenEstimator.estimate(100, null, "测试输入", "Hello world");
        assertThat(usage1.inputTokenCount()).isEqualTo(100);
        assertThat(usage1.outputTokenCount()).isEqualTo(3);
        assertThat(usage1.totalTokenCount()).isEqualTo(103);

        // prompt 缺失 -> 估算 prompt ("你好世界" -> 6)，已有真实 completionTokens=200
        TokenUsage usage2 = FallbackTokenEstimator.estimate(null, 200, "你好世界", "测试输出");
        assertThat(usage2.inputTokenCount()).isEqualTo(6);
        assertThat(usage2.outputTokenCount()).isEqualTo(200);
        assertThat(usage2.totalTokenCount()).isEqualTo(206);
    }
}
