package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.memory.store.InMemorySummaryChatMemoryStore;
import io.github.atengk.boot.ai.memory.store.SummaryChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 基于后台受管线程池非阻塞异步提炼的长会话摘要记忆实现
 * <p>
 * 遵循工业级高可用与高性能架构规范：
 * <ul>
 *   <li>双层上下文 (Two-Tier Context)：维护 {@code [System: Long-Term Summary] + [Recent N Turns Detailed History]}，兼顾长程事实记忆与近期高保真细节</li>
 *   <li>异步非阻塞 (Non-Blocking Latency)：通过 Core 受管异步线程池调度大模型提炼，用户请求与流式首字推流零毫秒阻塞</li>
 *   <li>纯净序列化与滑动 TTL：双模存储底座支持单机内存与 Redis 纯原生 JSON 存储，彻底杜绝 Jackson {@code @class} 污染并支持滑动续期</li>
 *   <li>双阈值触发：支持基于消息条数 (maxMessagesBeforeSummary) 与 Token 上限 (maxTokensBeforeSummary) 灵活触发提炼</li>
 *   <li>并发防重防抖：同一会话同一时刻只允许一个提炼任务运行，提炼期间并发写入的新消息无缝安全合并保留</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class SummaryChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryChatMemory.class);

    /**
     * 全局活跃提炼任务登记表，防御针对同一 memoryId 的并发重复提炼
     */
    private static final ConcurrentHashMap<Object, Boolean> ACTIVE_SUMMARIES = new ConcurrentHashMap<>();

    /**
     * 默认保底后台异步线程池
     */
    private static final Executor DEFAULT_EXECUTOR = ForkJoinPool.commonPool();

    /**
     * 默认长会话事实提炼提示词模板
     */
    public static final String DEFAULT_SUMMARY_PROMPT_TEMPLATE = """
            你是一个长对话事实提炼专家。请根据之前的对话摘要（如果有）以及后续新增的对话记录，生成一段精炼、保留关键事实、用户偏好和核心约束的最新长期摘要。
            要求：
            1. 保留所有关键事实、实体名称、用户偏好和核心约束。
            2. 剔除寒暄、过渡句及无关细节。
            3. 仅输出更新后的摘要正文，不要输出任何多余的解释。
            
            [之前摘要]
            %s
            
            [新增对话]
            %s
            """;

    private final Object id;
    private final SummaryChatMemoryStore store;
    private final ChatLanguageModel chatLanguageModel;
    private final Executor executor;
    private final Tokenizer tokenizer;
    private final int maxMessagesBeforeSummary;
    private final int maxTokensBeforeSummary;
    private final int keepRecentMessages;
    private final String summaryPrefix;
    private final String summaryPromptTemplate;

    private SummaryChatMemory(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "memoryId 不能为空");
        this.store = builder.store != null ? builder.store : new InMemorySummaryChatMemoryStore();
        this.chatLanguageModel = builder.chatLanguageModel;
        this.executor = builder.executor != null ? builder.executor : DEFAULT_EXECUTOR;
        this.tokenizer = builder.tokenizer != null ? builder.tokenizer : new HeuristicTokenizer();
        this.maxMessagesBeforeSummary = builder.maxMessagesBeforeSummary;
        this.maxTokensBeforeSummary = builder.maxTokensBeforeSummary;
        this.keepRecentMessages = Math.max(1, builder.keepRecentMessages);
        this.summaryPrefix = builder.summaryPrefix != null ? builder.summaryPrefix : "以下是此前对话的事实摘要约束：\n";
        this.summaryPromptTemplate = StringUtils.hasText(builder.summaryPromptTemplate)
                ? builder.summaryPromptTemplate
                : DEFAULT_SUMMARY_PROMPT_TEMPLATE;
    }

    /**
     * 创建 SummaryChatMemory 建造者
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        Assert.notNull(message, "ChatMessage 不能为空");

        // 1. 同步毫秒级追加新消息并持久化
        SummaryMemoryState currentState = store.getState(id);
        List<ChatMessage> list = new ArrayList<>(currentState.getMessages());
        list.add(message);
        SummaryMemoryState nextState = new SummaryMemoryState(currentState.getSummary(), list);
        store.updateState(id, nextState);

        // 2. 检查是否达到提炼阈值
        boolean shouldSummarize = false;
        if (maxMessagesBeforeSummary > 0 && list.size() >= maxMessagesBeforeSummary) {
            shouldSummarize = true;
        } else if (maxTokensBeforeSummary > 0) {
            int tokens = tokenizer.estimateTokenCountInMessages(list);
            if (tokens >= maxTokensBeforeSummary) {
                shouldSummarize = true;
            }
        }

        // 3. 满足阈值且具备大模型与待提炼轮次时，向后台受管线程池分发异步任务（零阻塞调用线程）
        if (shouldSummarize && chatLanguageModel != null && list.size() > keepRecentMessages) {
            triggerAsyncSummarization(currentState, list);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        SummaryMemoryState state = store.getState(id);
        List<ChatMessage> detailed = state.getMessages();
        if (!state.hasSummary()) {
            return detailed != null ? detailed : Collections.emptyList();
        }

        // 双层拼装：[SystemMessage: Long-Term Summary] + [Recent N Messages]
        List<ChatMessage> combined = new ArrayList<>();
        String summaryContent = (summaryPrefix != null ? summaryPrefix : "") + state.getSummary();
        combined.add(SystemMessage.from(summaryContent));
        if (detailed != null && !detailed.isEmpty()) {
            combined.addAll(detailed);
        }
        return Collections.unmodifiableList(combined);
    }

    @Override
    public void clear() {
        ACTIVE_SUMMARIES.remove(id);
        store.deleteState(id);
        log.debug("[AI-SUMMARY-MEMORY] 会话 [{}] 摘要与历史记忆已全部清空", id);
    }

    /**
     * 获取底层双层状态存储
     *
     * @return SummaryChatMemoryStore 实例
     */
    public SummaryChatMemoryStore getStore() {
        return store;
    }

    /**
     * 向后台受管线程池提交异步非阻塞长会话摘要提炼任务
     */
    private void triggerAsyncSummarization(SummaryMemoryState currentState, List<ChatMessage> currentMessages) {
        if (ACTIVE_SUMMARIES.putIfAbsent(id, Boolean.TRUE) != null) {
            log.debug("[AI-SUMMARY-MEMORY] 会话 [{}] 当前已有提炼任务在运行，跳过本次并发触发", id);
            return;
        }

        executor.execute(() -> {
            try {
                // 1. 划分早期待提炼消息与保留的近期消息
                int splitIndex = currentMessages.size() - keepRecentMessages;
                if (splitIndex <= 0) {
                    return;
                }
                List<ChatMessage> toSummarize = new ArrayList<>(currentMessages.subList(0, splitIndex));
                String formattedHistory = formatChatHistory(toSummarize);
                if (!StringUtils.hasText(formattedHistory)) {
                    return;
                }

                String prevSummary = StringUtils.hasText(currentState.getSummary()) ? currentState.getSummary() : "（无）";
                String prompt = String.format(summaryPromptTemplate, prevSummary, formattedHistory);

                // 2. 调用大语言模型提炼事实摘要
                Response<AiMessage> response = chatLanguageModel.generate(UserMessage.from(prompt));
                if (response != null && response.content() != null && StringUtils.hasText(response.content().text())) {
                    String newSummary = response.content().text().trim();

                    // 3. 安全状态合并：读取当前最新状态（提炼期间可能有新消息并发追加）
                    SummaryMemoryState latestState = store.getState(id);
                    List<ChatMessage> latestMessages = latestState.getMessages();
                    List<ChatMessage> retainedMessages;
                    if (latestMessages.size() >= splitIndex) {
                        retainedMessages = new ArrayList<>(latestMessages.subList(splitIndex, latestMessages.size()));
                    } else {
                        retainedMessages = new ArrayList<>(latestMessages);
                    }

                    SummaryMemoryState updatedState = new SummaryMemoryState(newSummary, retainedMessages);
                    store.updateState(id, updatedState);
                    log.info("[AI-SUMMARY-MEMORY] 会话 [{}] 异步长会话摘要提炼完成，保留最新详细消息: {} 条", id, retainedMessages.size());
                }
            } catch (Throwable t) {
                log.warn("[AI-SUMMARY-MEMORY] 会话 [{}] 异步提炼长会话摘要发生异常: {}", id, t.getMessage(), t);
            } finally {
                ACTIVE_SUMMARIES.remove(id);
            }
        });
    }

    private String formatChatHistory(List<ChatMessage> chatHistory) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : chatHistory) {
            if (msg instanceof UserMessage um) {
                sb.append("User: ").append(um.singleText()).append("\n");
            } else if (msg instanceof AiMessage am) {
                sb.append("Assistant: ").append(am.text()).append("\n");
            } else if (msg instanceof SystemMessage sm) {
                sb.append("System: ").append(sm.text()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * SummaryChatMemory 建造者
     */
    public static class Builder {
        private Object id;
        private SummaryChatMemoryStore store;
        private ChatLanguageModel chatLanguageModel;
        private Executor executor;
        private Tokenizer tokenizer;
        private int maxMessagesBeforeSummary = 20;
        private int maxTokensBeforeSummary = 4096;
        private int keepRecentMessages = 6;
        private String summaryPrefix;
        private String summaryPromptTemplate;

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder store(SummaryChatMemoryStore store) {
            this.store = store;
            return this;
        }

        public Builder chatLanguageModel(ChatLanguageModel chatLanguageModel) {
            this.chatLanguageModel = chatLanguageModel;
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder tokenizer(Tokenizer tokenizer) {
            this.tokenizer = tokenizer;
            return this;
        }

        public Builder maxMessagesBeforeSummary(int maxMessagesBeforeSummary) {
            this.maxMessagesBeforeSummary = maxMessagesBeforeSummary;
            return this;
        }

        public Builder maxTokensBeforeSummary(int maxTokensBeforeSummary) {
            this.maxTokensBeforeSummary = maxTokensBeforeSummary;
            return this;
        }

        public Builder keepRecentMessages(int keepRecentMessages) {
            this.keepRecentMessages = keepRecentMessages;
            return this;
        }

        public Builder summaryPrefix(String summaryPrefix) {
            this.summaryPrefix = summaryPrefix;
            return this;
        }

        public Builder summaryPromptTemplate(String summaryPromptTemplate) {
            this.summaryPromptTemplate = summaryPromptTemplate;
            return this;
        }

        public SummaryChatMemory build() {
            return new SummaryChatMemory(this);
        }
    }
}
