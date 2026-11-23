package io.github.atengk.boot.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.memory.store.InMemorySummaryChatMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SummaryChatMemory 核心行为、双层上下文与异步非阻塞提炼单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
class SummaryChatMemoryTest {

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    @DisplayName("双层上下文拼装：无摘要时原样返回；存在摘要时首部拼装长期摘要 SystemMessage")
    void testTwoTierContextComposition() {
        String memoryId = "mem_tier_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .keepRecentMessages(2)
                .summaryPrefix("【全局事实约束】\n")
                .build();

        // 1. 无摘要初始阶段：仅返回最新历史
        memory.add(UserMessage.from("第一条消息"));
        memory.add(AiMessage.from("第一条回复"));

        List<ChatMessage> messagesBefore = memory.messages();
        assertThat(messagesBefore).hasSize(2);
        assertThat(messagesBefore.get(0)).isInstanceOf(UserMessage.class);

        // 2. 模拟已有摘要：首部注入 SystemMessage
        store.updateState(memoryId, new SummaryMemoryState("用户是一名 Java 架构师", memory.messages()));

        List<ChatMessage> messagesAfter = memory.messages();
        assertThat(messagesAfter).hasSize(3);
        assertThat(messagesAfter.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messagesAfter.get(0)).text()).contains("【全局事实约束】", "用户是一名 Java 架构师");
        assertThat(messagesAfter.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messagesAfter.get(2)).isInstanceOf(AiMessage.class);
    }

    @Test
    @DisplayName("条数阈值触发异步非阻塞提炼：add 毫秒级返回，后台提炼后历史截断且摘要更新")
    void testAsyncSummarizationTriggerByMessageCount() throws Exception {
        String memoryId = "mem_async_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);

        CountDownLatch summaryTriggered = new CountDownLatch(1);
        when(mockModel.generate(any(UserMessage.class))).thenAnswer(invocation -> {
            summaryTriggered.countDown();
            return Response.from(AiMessage.from("提炼后的核心摘要：用户确认订单商品清单"));
        });

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .chatLanguageModel(mockModel)
                .executor(executorService)
                .maxMessagesBeforeSummary(4)
                .keepRecentMessages(2)
                .build();

        // 添加 3 条消息，未达到阈值 4
        memory.add(UserMessage.from("消息 1"));
        memory.add(AiMessage.from("回复 1"));
        memory.add(UserMessage.from("消息 2"));
        assertThat(memory.messages()).hasSize(3);
        verify(mockModel, never()).generate(any(UserMessage.class));

        // 添加第 4 条消息，触发异步提炼
        long start = System.currentTimeMillis();
        memory.add(AiMessage.from("回复 2"));
        long elapsed = System.currentTimeMillis() - start;
        // 验证 add 立即返回，不被大模型调用阻塞
        assertThat(elapsed).isLessThan(200);

        // 等待异步提炼完成
        boolean reached = summaryTriggered.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();

        // 等待后台持久化完成
        Thread.sleep(100);

        // 验证状态：摘要已生成，且详细消息截断至最新的 2 条，总双层上下文为 1 条 SystemMessage + 2 条详细消息
        SummaryMemoryState state = store.getState(memoryId);
        assertThat(state.getSummary()).isEqualTo("提炼后的核心摘要：用户确认订单商品清单");
        assertThat(state.getMessages()).hasSize(2);
        assertThat(((UserMessage) state.getMessages().get(0)).singleText()).isEqualTo("消息 2");
        assertThat(((AiMessage) state.getMessages().get(1)).text()).isEqualTo("回复 2");

        List<ChatMessage> finalMessages = memory.messages();
        assertThat(finalMessages).hasSize(3);
        assertThat(finalMessages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) finalMessages.get(0)).text()).contains("提炼后的核心摘要：用户确认订单商品清单");
    }

    @Test
    @DisplayName("Token 阈值触发异步提炼：详细消息累计估算超标时自动触发")
    void testAsyncSummarizationTriggerByTokenCount() throws Exception {
        String memoryId = "mem_token_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);

        CountDownLatch summaryTriggered = new CountDownLatch(1);
        when(mockModel.generate(any(UserMessage.class))).thenAnswer(invocation -> {
            summaryTriggered.countDown();
            return Response.from(AiMessage.from("摘要：讨论超长技术文档架构"));
        });

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .chatLanguageModel(mockModel)
                .executor(executorService)
                .maxMessagesBeforeSummary(0) // 关闭条数触发
                .maxTokensBeforeSummary(30)  // 设定较小 Token 阈值
                .keepRecentMessages(1)
                .build();

        // 写入一条较长消息击穿 Token 阈值
        memory.add(UserMessage.from("这是一段非常长的高密度技术文档说明，旨在快速消耗并击穿设定的 Token 阈值以触发长会话后台异步摘要提炼。"));
        memory.add(AiMessage.from("收到长消息"));

        boolean reached = summaryTriggered.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();

        Thread.sleep(100);
        SummaryMemoryState state = store.getState(memoryId);
        assertThat(state.getSummary()).contains("摘要：讨论超长技术文档架构");
    }

    @Test
    @DisplayName("并发防重提炼：后台提炼期间并发写入不触发二次重复提炼，且新消息安全保留")
    void testConcurrentAddDoesNotDuplicateSummarization() throws Exception {
        String memoryId = "mem_concur_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch pauseLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        when(mockModel.generate(any(UserMessage.class))).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            pauseLatch.await(3, TimeUnit.SECONDS);
            finishLatch.countDown();
            return Response.from(AiMessage.from("并发保护下的新摘要"));
        });

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .chatLanguageModel(mockModel)
                .executor(executorService)
                .maxMessagesBeforeSummary(3)
                .keepRecentMessages(1)
                .build();

        memory.add(UserMessage.from("1"));
        memory.add(UserMessage.from("2"));
        // 触发提炼
        memory.add(UserMessage.from("3"));

        // 在提炼正在进行时，并发追加新消息 4 和 5
        memory.add(UserMessage.from("4"));
        memory.add(UserMessage.from("5"));

        // 释放阻塞的大模型调用
        pauseLatch.countDown();
        finishLatch.await(3, TimeUnit.SECONDS);
        Thread.sleep(150);

        // 验证大模型提炼仅被调用了 1 次，没有发生并发重复调用
        assertThat(callCount.get()).isEqualTo(1);

        // 验证并发追加的新消息 4 和 5 没有丢失
        SummaryMemoryState state = store.getState(memoryId);
        assertThat(state.getSummary()).isEqualTo("并发保护下的新摘要");
        // 保留的早期最后一条 (3) + 提炼期间追加的 (4, 5)
        List<ChatMessage> retained = state.getMessages();
        assertThat(retained).isNotEmpty();
        List<String> texts = retained.stream().map(m -> ((UserMessage) m).singleText()).toList();
        assertThat(texts).contains("4", "5");
    }

    @Test
    @DisplayName("提炼异常容错：大模型抛出异常时 add 不受影响且释放提炼锁，下次可再次触发")
    void testSummarizationFailureDoesNotBreakChatMemory() throws Exception {
        String memoryId = "mem_err_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);

        CountDownLatch firstAttempt = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);

        when(mockModel.generate(any(UserMessage.class)))
                .thenAnswer(invocation -> {
                    firstAttempt.countDown();
                    throw new RuntimeException("LLM service unavailable 503");
                })
                .thenAnswer(invocation -> {
                    secondAttempt.countDown();
                    return Response.from(AiMessage.from("第二次恢复成功摘要"));
                });

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .chatLanguageModel(mockModel)
                .executor(executorService)
                .maxMessagesBeforeSummary(2)
                .keepRecentMessages(1)
                .build();

        // 第一次触发提炼（抛出异常）
        memory.add(UserMessage.from("A"));
        memory.add(UserMessage.from("B"));

        boolean firstDone = firstAttempt.await(3, TimeUnit.SECONDS);
        assertThat(firstDone).isTrue();
        Thread.sleep(100);

        // 异常时未更新摘要，保留原有消息
        SummaryMemoryState state1 = store.getState(memoryId);
        assertThat(state1.getSummary()).isEmpty();
        assertThat(state1.getMessages()).hasSize(2);

        // 再次添加消息，由于锁已在 finally 释放，下一次满足条件可再次触发提炼
        memory.add(UserMessage.from("C"));
        boolean secondDone = secondAttempt.await(3, TimeUnit.SECONDS);
        assertThat(secondDone).isTrue();
        Thread.sleep(100);

        SummaryMemoryState state2 = store.getState(memoryId);
        assertThat(state2.getSummary()).isEqualTo("第二次恢复成功摘要");
    }

    @Test
    @DisplayName("对称生命周期与清空：clear 清理存储与会话上下文")
    void testClearLifecycle() {
        String memoryId = "mem_clear_01";
        InMemorySummaryChatMemoryStore store = new InMemorySummaryChatMemoryStore();

        SummaryChatMemory memory = SummaryChatMemory.builder()
                .id(memoryId)
                .store(store)
                .build();

        memory.add(UserMessage.from("测试"));
        assertThat(memory.messages()).hasSize(1);
        assertThat(store.exists(memoryId)).isTrue();

        memory.clear();
        assertThat(memory.messages()).isEmpty();
        assertThat(store.exists(memoryId)).isFalse();
    }
}
