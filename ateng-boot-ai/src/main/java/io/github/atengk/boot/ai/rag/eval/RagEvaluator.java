package io.github.atengk.boot.ai.rag.eval;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.util.ValidatorUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 显式按需 RAG 检索生成质量评测门面 (LLM-as-a-Judge)
 * <p>
 * 仅在业务显式调用 {@link #evaluate(RagEvaluationRequest)} 时触发结构化 LLM 裁判，
 * 绝不自动挂载至 {@link AiTemplate} 常规对话热路径或监听链，避免额外延时与 Token 成本膨胀。
 * 依托 {@link AiTemplate#chatForObject(String, String, Class)} 产出涵盖
 * 忠实度（faithfulness）、答案相关性（answerRelevance）、上下文召回率（contextRecall）
 * 与综合评分（overallScore）的结构化评测报告。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public class RagEvaluator {

    private final AiTemplate directAiTemplate;
    private final ObjectProvider<AiTemplate> aiTemplateProvider;

    public RagEvaluator(AiTemplate aiTemplate) {
        Assert.notNull(aiTemplate, "aiTemplate 不能为空");
        this.directAiTemplate = aiTemplate;
        this.aiTemplateProvider = null;
    }

    public RagEvaluator(ObjectProvider<AiTemplate> aiTemplateProvider) {
        this.directAiTemplate = null;
        this.aiTemplateProvider = aiTemplateProvider;
    }

    /**
     * 对单轮 RAG 检索与回答结果执行显式质量评测
     *
     * @param request 评测请求参数（包含问题、回答、检索上下文与可选参考标准答案）
     * @return 归一化至 {@code [0.0, 1.0]} 区间的多维质量评测报告
     */
    public RagEvaluationReport evaluate(RagEvaluationRequest request) {
        Assert.notNull(request, "RAG 评测请求不能为空");
        ValidatorUtil.validate(request);

        List<String> validContexts = request.getRetrievedContexts().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (validContexts.isEmpty()) {
            throw new IllegalArgumentException("retrievedContexts 中至少需要一条非空检索上下文片段");
        }

        AiTemplate aiTemplate = resolveAiTemplate();
        if (aiTemplate == null) {
            throw new AiException(
                    AiResultCode.AI_MODEL_NOT_FOUND,
                    "RagEvaluator 未检测到可用的 AiTemplate 实例，无法执行 LLM 质量评测"
            );
        }

        String prompt = buildJudgePrompt(request, validContexts);
        String targetModel = StringUtils.hasText(request.getModelName()) ? request.getModelName().trim() : null;

        RagEvaluationReport rawReport;
        try {
            rawReport = aiTemplate.chatForObject(targetModel, prompt, RagEvaluationReport.class);
        } catch (AiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AiExceptionTranslator.translate("RAG 质量评测 LLM-as-a-Judge 执行失败", ex);
        }

        if (rawReport == null) {
            throw new AiException(
                    AiResultCode.AI_RESPONSE_PARSE_ERROR,
                    "RAG 质量评测未返回有效的 RagEvaluationReport 结构化结果"
            );
        }

        return normalizeReport(rawReport);
    }

    /**
     * 探测底层 AiTemplate 是否就绪
     *
     * @return 是否可执行评测
     */
    public boolean isAvailable() {
        return resolveAiTemplate() != null;
    }

    private AiTemplate resolveAiTemplate() {
        if (this.directAiTemplate != null) {
            return this.directAiTemplate;
        }
        return this.aiTemplateProvider != null ? this.aiTemplateProvider.getIfAvailable() : null;
    }

    private String buildJudgePrompt(RagEvaluationRequest request, List<String> validContexts) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("你是一名严谨、客观的 RAG（检索增强生成）系统质量评测裁判。\n")
                .append("请严格基于下方输入，从三个核心维度给出 [0.0, 1.0] 区间浮点评分（保留两位小数），并给出综合评分与中文裁决理由。\n\n")
                .append("【评测指标定义】\n")
                .append("1. faithfulness (0.0~1.0): 忠实度，评估生成回答中的全部事实声明是否均可由检索上下文直接支撑，无幻觉捏造或越界推断。\n")
                .append("2. answerRelevance (0.0~1.0): 答案相关性，评估生成回答是否切中用户问题核心并完整回答，无冗余跑题。\n")
                .append("3. contextRecall (0.0~1.0): 上下文召回率，评估检索上下文对参考标准答案（若未提供参考答案则对照完整解答问题所需核心要点）的覆盖度。\n")
                .append("4. overallScore (0.0~1.0): 综合质量得分。\n")
                .append("5. reasoning (String): 简洁客观的中文裁决依据，指出证据支撑、遗漏点或潜在幻觉。\n\n")
                .append("【用户问题 (question)】\n").append(request.getQuestion().trim()).append("\n\n")
                .append("【检索上下文 (contexts)】\n");
        for (int i = 0; i < validContexts.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(validContexts.get(i)).append("\n");
        }
        sb.append("\n【系统回答 (answer)】\n").append(request.getAnswer().trim()).append("\n\n");
        if (StringUtils.hasText(request.getReferenceAnswer())) {
            sb.append("【参考标准答案 (referenceAnswer)】\n").append(request.getReferenceAnswer().trim()).append("\n");
        } else {
            sb.append("【参考标准答案 (referenceAnswer)】\n未提供（请依据问题要点与上下文契合度评估 contextRecall）。\n");
        }
        return sb.toString();
    }

    private RagEvaluationReport normalizeReport(RagEvaluationReport raw) {
        double faithfulness = clampScore(raw.getFaithfulness());
        double answerRelevance = clampScore(raw.getAnswerRelevance());
        double contextRecall = clampScore(raw.getContextRecall());
        double overall = raw.getOverallScore() != null
                ? clampScore(raw.getOverallScore())
                : roundTwoDecimals((faithfulness + answerRelevance + contextRecall) / 3.0);
        String reasoning = StringUtils.hasText(raw.getReasoning()) ? raw.getReasoning().trim() : "";
        return new RagEvaluationReport(faithfulness, answerRelevance, contextRecall, overall, reasoning);
    }

    private static double clampScore(Double score) {
        if (score == null || Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, score));
        return roundTwoDecimals(clamped);
    }

    private static double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
