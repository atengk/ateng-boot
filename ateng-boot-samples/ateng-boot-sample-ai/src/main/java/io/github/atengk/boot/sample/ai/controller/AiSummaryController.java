package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.memory.SummaryChatMemory;
import io.github.atengk.boot.ai.memory.SummaryMemoryState;
import io.github.atengk.boot.ai.memory.store.InMemorySummaryChatMemoryStore;
import io.github.atengk.boot.ai.memory.store.SummaryChatMemoryStore;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiSummaryChatRequest;
import io.github.atengk.boot.sample.ai.vo.AiSummaryChatVo;
import io.github.atengk.boot.sample.ai.vo.AiSummaryClearVo;
import io.github.atengk.boot.sample.ai.vo.AiSummaryStateVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 长会话异步摘要记忆体验接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Validated
@RestController
@RequestMapping("/sample/ai/summary")
@RequiredArgsConstructor
public class AiSummaryController {

    private final ObjectProvider<SummaryChatMemoryStore> summaryStoreProvider;
    private final ObjectProvider<ChatLanguageModel> chatModelProvider;
    private final ObjectProvider<TaskExecutor> taskExecutorProvider;
    private final AtengAiProperties properties;

    // 单机兜底存储，确保无外部 Redis 时同样自洽
    private final SummaryChatMemoryStore fallbackStore = new InMemorySummaryChatMemoryStore();

    private SummaryChatMemoryStore getStore() {
        SummaryChatMemoryStore store = summaryStoreProvider.getIfAvailable();
        return store != null ? store : fallbackStore;
    }

    private SummaryChatMemory buildMemory(String sessionId) {
        SummaryChatMemoryStore store = getStore();
        ChatLanguageModel model = chatModelProvider.getIfAvailable();
        TaskExecutor executor = taskExecutorProvider.getIfAvailable();

        int maxMessages = properties.getChatMemory() != null && properties.getChatMemory().getMaxMessagesBeforeSummary() > 0
                ? properties.getChatMemory().getMaxMessagesBeforeSummary()
                : 4; // Sample 默认 4 条即触发提炼，方便快速演示
        int keepRecent = properties.getChatMemory() != null && properties.getChatMemory().getKeepRecentMessages() > 0
                ? properties.getChatMemory().getKeepRecentMessages()
                : 2;

        return SummaryChatMemory.builder()
                .id(sessionId)
                .store(store)
                .chatLanguageModel(model)
                .executor(executor)
                .maxMessagesBeforeSummary(maxMessages)
                .keepRecentMessages(keepRecent)
                .build();
    }

    /**
     * 长会话多轮输入对话体验 (自动维护双层记忆并在达到阈值时异步后台提炼全局摘要)
     *
     * @param request 对话入参
     * @return 当前回复与即时摘要状态
     */
    @PostMapping("/chat")
    public Result<AiSummaryChatVo> chat(@Valid @RequestBody AiSummaryChatRequest request) {
        String sessionId = request.getSessionId();
        SummaryChatMemory memory = buildMemory(sessionId);

        // 1. 追加用户输入并持久化
        memory.add(UserMessage.from(request.getMessage()));

        // 2. 调用大模型生成回复
        ChatLanguageModel model = chatModelProvider.getIfAvailable();
        String replyText;
        if (model != null) {
            Response<AiMessage> response = model.generate(memory.messages());
            replyText = response.content().text();
        } else {
            replyText = "已收到关于 [" + request.getMessage() + "] 的信息并存入会话记忆。";
        }

        // 3. 将助手回复追加到会话记忆中 (若达到阈值将自动触发异步后台提炼)
        memory.add(AiMessage.from(replyText));

        // 4. 查询当前最新双层状态
        SummaryMemoryState state = getStore().getState(sessionId);
        AiSummaryChatVo vo = AiSummaryChatVo.builder()
                .sessionId(sessionId)
                .reply(replyText)
                .summary(state.hasSummary() ? state.getSummary() : "（历史轮次未达到阈值，尚未触发长会话摘要提炼）")
                .recentMessagesCount(state.getMessages() != null ? state.getMessages().size() : 0)
                .build();
        return Result.success(vo);
    }

    /**
     * 查看指定会话当前持久化的双层上下文记忆状态 (长期事实摘要 + 近期明细)
     *
     * @param sessionId 会话标识
     * @return 状态详情
     */
    @GetMapping("/state")
    public Result<AiSummaryStateVo> getState(@RequestParam("sessionId") @NotBlank(message = "sessionId 不能为空") String sessionId) {
        SummaryMemoryState state = getStore().getState(sessionId);
        List<String> formattedMessages = new ArrayList<>();
        if (state.getMessages() != null) {
            for (ChatMessage msg : state.getMessages()) {
                String content = msg instanceof UserMessage u ? u.singleText()
                        : (msg instanceof AiMessage a ? a.text() : msg.toString());
                formattedMessages.add("[" + msg.type() + "] " + content);
            }
        }

        AiSummaryStateVo vo = AiSummaryStateVo.builder()
                .sessionId(sessionId)
                .summary(state.hasSummary() ? state.getSummary() : "（暂无提炼摘要）")
                .recentMessages(formattedMessages)
                .build();
        return Result.success(vo);
    }

    /**
     * 清空指定会话的全部上下文记忆与摘要
     *
     * @param sessionId 会话标识
     * @return 操作成功结果
     */
    @PostMapping("/clear")
    public Result<AiSummaryClearVo> clear(@RequestParam("sessionId") @NotBlank(message = "sessionId 不能为空") String sessionId) {
        SummaryChatMemory memory = buildMemory(sessionId);
        memory.clear();
        return Result.success(AiSummaryClearVo.builder()
                .sessionId(sessionId)
                .cleared(true)
                .message("会话事实摘要与历史明细已全部清空")
                .build());
    }
}
