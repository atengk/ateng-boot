package io.github.atengk.boot.ai.rag.query;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于大语言模型的多轮对话检索查询改写器
 * <p>
 * 依托底层轻量 {@link ChatLanguageModel}，将多轮历史中的代词与上下文省略进行指代消解，
 * 改写为独立、高信息密度的检索 Query；内置启发式短路前置探测（单轮或无代词时免除调用），保障极速响应。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class LlmQueryTransformer implements QueryTransformer {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryTransformer.class);

    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            ".*(?:它|其|这|那|该|第[一二三四两1234]|上述|前面|刚才|还有吗|多少钱|怎么样|为什么|怎么做|多少|有哪些|是什么|区别|优势|缺点|在哪|何时|谁|怎么).*",
            Pattern.DOTALL
    );

    public static final String DEFAULT_PROMPT_TEMPLATE = """
            你是一个专业知识库搜索查询改写助手。请结合以下历史对话记录，将用户的最新问题改写为一个独立、完整且消除代词指代（如“它”、“其”、“后者”等）的搜索查询。
            如果最新问题本身已经语义完整、不需要结合历史对话即可独立检索，请务必直接原样输出最新问题。
            严禁添加任何引号、前缀说明、分析解释或多余字符，只输出改写后的最终纯文本。
            
            历史对话：
            %s
            
            最新问题：
            %s
            """;

    private final ChatLanguageModel chatModel;
    private final int maxHistoryMessages;

    public LlmQueryTransformer(ChatLanguageModel chatModel) {
        this(chatModel, 6);
    }

    public LlmQueryTransformer(ChatLanguageModel chatModel, int maxHistoryMessages) {
        Assert.notNull(chatModel, "chatModel 不能为空");
        this.chatModel = chatModel;
        this.maxHistoryMessages = Math.max(2, maxHistoryMessages);
    }

    @Override
    public Query transform(Query query, List<ChatMessage> chatHistory) {
        if (query == null || !StringUtils.hasText(query.text())) {
            return query;
        }

        // 1. 快速短路保护：历史为空或只有 1 轮时直接免除改写，零延迟
        if (chatHistory == null || chatHistory.size() <= 1) {
            return query;
        }

        // 2. 启发式代词探测：若句子较长且明显不包含代词指代特征，直接跳过 LLM 改写
        String text = query.text().trim();
        if (text.length() > 30 && !PRONOUN_PATTERN.matcher(text).matches()) {
            return query;
        }

        // 3. 构建最近 N 轮上下文对话摘要
        String formattedHistory = formatChatHistory(chatHistory);
        if (!StringUtils.hasText(formattedHistory)) {
            return query;
        }

        String prompt = String.format(DEFAULT_PROMPT_TEMPLATE, formattedHistory, text);

        try {
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            if (response != null && response.content() != null && StringUtils.hasText(response.content().text())) {
                String rewritten = sanitizeRewrittenQuery(response.content().text(), text);
                log.debug("[AI-RAG-REWRITE] 多轮查询改写完成: 原词='{}' -> 改写词='{}'", text, rewritten);
                if (query.metadata() != null) {
                    return Query.from(rewritten, query.metadata());
                }
                return Query.from(rewritten);
            }
        } catch (Exception e) {
            log.warn("[AI-RAG-REWRITE] 调用大模型改写查询发生异常 ({})，自动降级采用原查询", e.getMessage());
        }

        return query;
    }

    private String formatChatHistory(List<ChatMessage> chatHistory) {
        int size = chatHistory.size();
        int startIndex = Math.max(0, size - maxHistoryMessages);
        List<ChatMessage> subList = chatHistory.subList(startIndex, size);

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : subList) {
            if (msg instanceof UserMessage um) {
                sb.append("User: ").append(um.singleText()).append("\n");
            } else if (msg instanceof AiMessage am) {
                sb.append("Assistant: ").append(am.text()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String sanitizeRewrittenQuery(String rawRewritten, String originalText) {
        String cleaned = rawRewritten.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.startsWith("“") && cleaned.endsWith("”") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.isBlank()) {
            return originalText;
        }
        return cleaned;
    }

    public ChatLanguageModel getChatModel() {
        return chatModel;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }
}
