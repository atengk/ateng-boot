package io.github.atengk.boot.ai.observability;

import dev.langchain4j.model.output.TokenUsage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 大模型流式调用保底 Token 估算器
 * <p>
 * 纯函数无状态工具类。当远程大模型网关流式推流（如 SSE 打字机）未返回 TokenUsage 元数据时，
 * 基于中英双语启发式加权算法（中文字符 * 1.5 + 英文/数字单词 * 1.3）自动保底推算 Token 消耗量，
 * 消除监控大盘与审计报表在流式调用下的断崖式空白。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class FallbackTokenEstimator {

    /**
     * 英文与数字词元正则模式 (按连续字母或数字切分词元)
     */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

    private FallbackTokenEstimator() {
        // 工具类禁止实例化
    }

    /**
     * 估算指定文本所消耗的 Token 数量
     * <p>
     * 启发式算法规则：
     * 1. 汉字字符权重为 1.5 Token/字；
     * 2. 英文与数字词元权重为 1.3 Token/词；
     * 3. 若文本非空且非纯空白，保底至少计为 1 Token。
     * </p>
     *
     * @param text 待估算文本
     * @return 估算的 Token 数量（文本为空或空白时返回 0）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int chineseCharCount = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                chineseCharCount++;
            }
            i += Character.charCount(cp);
        }

        Matcher matcher = WORD_PATTERN.matcher(text);
        int englishWordCount = 0;
        while (matcher.find()) {
            englishWordCount++;
        }

        double estimated = (chineseCharCount * 1.5) + (englishWordCount * 1.3);
        int tokens = (int) Math.round(estimated);
        return Math.max(1, tokens);
    }

    /**
     * 基于输入提示词与模型响应文本完整估算 TokenUsage
     *
     * @param promptText   输入提示词
     * @param responseText 模型响应内容
     * @return 估算的 TokenUsage 实例
     */
    public static TokenUsage estimate(String promptText, String responseText) {
        int promptTokens = estimateTokens(promptText);
        int completionTokens = estimateTokens(responseText);
        int totalTokens = promptTokens + completionTokens;
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /**
     * 结合已有部分 TokenUsage 与文本进行缺省保底估算
     * <p>
     * 若已获取到真实 promptTokens 或 completionTokens 则优先沿用真实值，仅对缺失或为 0 的维度执行保底估算。
     * </p>
     *
     * @param promptTokens     已有的输入 Token 数 (可为 null)
     * @param completionTokens 已有的输出 Token 数 (可为 null)
     * @param promptText       输入提示词文本
     * @param responseText     模型响应文本
     * @return 合并或估算后的 TokenUsage 实例
     */
    public static TokenUsage estimate(Integer promptTokens, Integer completionTokens, String promptText, String responseText) {
        int prompt = (promptTokens != null && promptTokens > 0) ? promptTokens : estimateTokens(promptText);
        int completion = (completionTokens != null && completionTokens > 0) ? completionTokens : estimateTokens(responseText);
        return new TokenUsage(prompt, completion, prompt + completion);
    }
}
