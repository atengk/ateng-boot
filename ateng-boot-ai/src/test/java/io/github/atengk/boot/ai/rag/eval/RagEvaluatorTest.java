package io.github.atengk.boot.ai.rag.eval;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式按需 RAG 质量评测器 (RagEvaluator) 单元测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@DisplayName("显式按需 RAG 质量评测器 (RagEvaluator) 单元测试")
class RagEvaluatorTest {

    @Test
    @DisplayName("依托真实 AiTemplate.chatForObject 完成 LLM-as-a-Judge 评测并解析全部指标")
    @SuppressWarnings("unchecked")
    void shouldEvaluateRagMetricsThroughRealAiTemplate() {
        ChatLanguageModel chatModel = Mockito.mock(ChatLanguageModel.class);
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {
                  "faithfulness": 0.96,
                  "answerRelevance": 0.92,
                  "contextRecall": 0.88,
                  "overallScore": 0.92,
                  "reasoning": "回答完全忠于分布式锁与 Redisson 检索片段，无幻觉捏造。"
                }
                """)));

        AiModelRegistry registry = new AiModelRegistry();
        registry.register(AiModelRegistry.DEFAULT_MODEL_NAME, chatModel);
        AiTemplate aiTemplate = new AiTemplate(registry, new AtengAiProperties());
        RagEvaluator evaluator = new RagEvaluator(aiTemplate);

        RagEvaluationRequest request = RagEvaluationRequest.builder()
                .question("Redisson 分布式锁看门狗默认续期周期是多少？")
                .answer("Redisson 分布式锁看门狗默认锁过期时间为 30 秒，每隔 10 秒触发一次续期。")
                .retrievedContexts(List.of(
                        "Redisson WatchDog 看门狗机制默认 lockWatchdogTimeout 为 30000 毫秒。",
                        "后台定时任务会在 lockWatchdogTimeout / 3 即每 10 秒自动延长锁持有时间。"
                ))
                .referenceAnswer("默认 lockWatchdogTimeout 为 30 秒，每 10 秒续期一次。")
                .build();

        RagEvaluationReport report = evaluator.evaluate(request);

        assertThat(evaluator.isAvailable()).isTrue();
        assertThat(report.getFaithfulness()).isEqualTo(0.96);
        assertThat(report.getAnswerRelevance()).isEqualTo(0.92);
        assertThat(report.getContextRecall()).isEqualTo(0.88);
        assertThat(report.getOverallScore()).isEqualTo(0.92);
        assertThat(report.getReasoning()).contains("无幻觉");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(1)).generate(captor.capture());
        String promptText = captor.getValue().get(captor.getValue().size() - 1).toString();
        assertThat(promptText).contains("Redisson WatchDog");
        assertThat(promptText).contains("lockWatchdogTimeout");
    }

    @Test
    @DisplayName("超界分数自动归一化至 [0.0, 1.0]，缺省 overallScore 自动均值回填，且支持指定评测模型")
    void shouldNormalizeScoresAndRouteCustomModel() {
        AiTemplate aiTemplate = Mockito.mock(AiTemplate.class);
        when(aiTemplate.chatForObject(
                eq("judge-gpt"),
                Mockito.anyString(),
                eq(RagEvaluationReport.class)))
                .thenReturn(new RagEvaluationReport(1.5, -0.2, 0.75, null, "  部分依据未覆盖  "));

        RagEvaluator evaluator = new RagEvaluator(aiTemplate);
        RagEvaluationReport report = evaluator.evaluate(RagEvaluationRequest.builder()
                .question("测试问题")
                .answer("测试回答")
                .retrievedContexts(List.of("上下文片段 A"))
                .modelName("judge-gpt")
                .build());

        assertThat(report.getFaithfulness()).isEqualTo(1.0);
        assertThat(report.getAnswerRelevance()).isEqualTo(0.0);
        assertThat(report.getContextRecall()).isEqualTo(0.75);
        // (1.0 + 0.0 + 0.75) / 3 = 0.5833 -> 0.58
        assertThat(report.getOverallScore()).isEqualTo(0.58);
        assertThat(report.getReasoning()).isEqualTo("部分依据未覆盖");
    }

    @Test
    @DisplayName("日常 AiTemplate.chat 热路径零介入：普通对话不触发任何评测请求")
    void shouldNotTriggerEvaluationOnNormalChatHotPath() {
        ChatLanguageModel chatModel = Mockito.mock(ChatLanguageModel.class);
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("普通回复")));

        AiModelRegistry registry = new AiModelRegistry();
        registry.register(AiModelRegistry.DEFAULT_MODEL_NAME, chatModel);
        AiTemplate aiTemplate = new AiTemplate(registry, new AtengAiProperties());
        RagEvaluator evaluator = new RagEvaluator(aiTemplate);
        assertThat(evaluator.isAvailable()).isTrue();

        String reply = aiTemplate.chat("你好");
        assertThat(reply).isEqualTo("普通回复");
        verify(chatModel, times(1)).generate(anyList());
    }

    @Test
    @DisplayName("Fail-Fast 校验：空入参、空白上下文与底层模型缺失时快速失败并明确抛错")
    void shouldFailFastOnInvalidRequestOrUnavailableTemplate() {
        AiTemplate aiTemplate = Mockito.mock(AiTemplate.class);
        RagEvaluator evaluator = new RagEvaluator(aiTemplate);

        assertThatThrownBy(() -> evaluator.evaluate(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> evaluator.evaluate(RagEvaluationRequest.builder()
                .question(" ")
                .answer("answer")
                .retrievedContexts(List.of("ctx"))
                .build()))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> evaluator.evaluate(RagEvaluationRequest.builder()
                .question("q")
                .answer("a")
                .retrievedContexts(List.of("   ", ""))
                .build()))
                .isInstanceOf(IllegalArgumentException.class);

        RagEvaluator unavailableEvaluator = new RagEvaluator((org.springframework.beans.factory.ObjectProvider<AiTemplate>) null);
        assertThat(unavailableEvaluator.isAvailable()).isFalse();
        assertThatThrownBy(() -> unavailableEvaluator.evaluate(RagEvaluationRequest.builder()
                .question("q")
                .answer("a")
                .retrievedContexts(List.of("ctx"))
                .build()))
                .isInstanceOf(AiException.class);
    }
}
