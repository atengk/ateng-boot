package io.github.atengk.boot.ai.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequest;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponse;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 大模型全维可观测审计监听器单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AtengAiObservabilityListenerTest {

    private AtengAiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AtengAiProperties();
        properties.getAudit().setEnabled(true);
        properties.getAudit().setMaskPii(true);
        properties.getAudit().setLogPayload(true);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("正常成功对话生命周期：耗时统计、四维上下文透传、PII 脱敏与 AuditRecord 分发")
    void testSuccessfulChatAudit() {
        // 1. 设置主调用线程四维上下文
        MDC.put("traceId", "trace-audit-001");
        UserContextHolder.setUserId("user-888");
        TenantContextHolder.setTenantId("tenant-finance-01");

        AtomicReference<AiAuditRecord> capturedRecord = new AtomicReference<>();
        AiAuditLogHandler handler = capturedRecord::set;

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(handler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder()
                .model("deepseek-chat")
                .messages(List.of(UserMessage.from("用户手机号是 13812345678，请查询余额")))
                .build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());

        // 2. 发起请求
        listener.onRequest(reqContext);

        // 3. 构造响应（包含 PII 信息与 Token 消耗）
        ChatModelResponse response = ChatModelResponse.builder()
                .id("resp-001")
                .model("deepseek-chat")
                .tokenUsage(new TokenUsage(15, 25, 40))
                .aiMessage(AiMessage.from("已查询到手机 13812345678 对应的账户余额为 500 元。"))
                .build();
        ChatModelResponseContext respContext = new ChatModelResponseContext(response, request, reqContext.attributes());

        // 4. 收到响应
        listener.onResponse(respContext);

        // 5. 强断言审计记录
        AiAuditRecord record = capturedRecord.get();
        assertThat(record).isNotNull();
        assertThat(record.getTraceId()).isEqualTo("trace-audit-001");
        assertThat(record.getUserId()).isEqualTo("user-888");
        assertThat(record.getTenantId()).isEqualTo("tenant-finance-01");
        assertThat(record.getModelName()).isEqualTo("deepseek-chat");
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(record.getPromptTokens()).isEqualTo(15);
        assertThat(record.getCompletionTokens()).isEqualTo(25);
        assertThat(record.getTotalTokens()).isEqualTo(40);
        assertThat(record.isEstimated()).isFalse();

        // 6. 验证 PII 正则脱敏
        assertThat(record.getPromptText()).contains("138****5678");
        assertThat(record.getPromptText()).doesNotContain("13812345678");
        assertThat(record.getResponseText()).contains("138****5678");
        assertThat(record.getResponseText()).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("异构工作线程回调响应时：四维上下文能确定性精准还原并在 finally 现场清理")
    void testContextRestorationInDifferentThread() throws Exception {
        MDC.put("traceId", "trace-thread-cross");
        UserContextHolder.setUserId("u-cross");
        TenantContextHolder.setTenantId("t-cross");

        AtomicReference<String> restoredTraceId = new AtomicReference<>();
        AtomicReference<String> restoredUserId = new AtomicReference<>();
        AtomicReference<String> restoredTenantId = new AtomicReference<>();

        AiAuditLogHandler handler = record -> {
            restoredTraceId.set(MDC.get("traceId"));
            restoredUserId.set(UserContextHolder.getUserId());
            restoredTenantId.set(TenantContextHolder.getTenantId());
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(handler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder()
                .model("gpt-4o")
                .messages(List.of(UserMessage.from("hello")))
                .build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listener.onRequest(reqContext);

        // 在完全独立的干净工作线程中触发 onResponse
        Thread asyncThread = new Thread(() -> {
            // 当前线程原本无任何上下文
            assertThat(MDC.get("traceId")).isNull();
            assertThat(UserContextHolder.getUserId()).isNull();

            ChatModelResponse response = ChatModelResponse.builder()
                    .model("gpt-4o")
                    .tokenUsage(new TokenUsage(5, 5, 10))
                    .aiMessage(AiMessage.from("world"))
                    .build();
            ChatModelResponseContext respContext = new ChatModelResponseContext(response, request, reqContext.attributes());

            listener.onResponse(respContext);

            // 执行完毕后现场必须被完全清理
            assertThat(MDC.get("traceId")).isNull();
            assertThat(UserContextHolder.getUserId()).isNull();
        });

        asyncThread.start();
        asyncThread.join();

        assertThat(restoredTraceId.get()).isEqualTo("trace-thread-cross");
        assertThat(restoredUserId.get()).isEqualTo("u-cross");
        assertThat(restoredTenantId.get()).isEqualTo("t-cross");
    }

    @Test
    @DisplayName("普通错误场景：标记 status 为 ERROR 并记录异常堆栈")
    void testErrorAudit() {
        MDC.put("traceId", "trace-err-01");
        AtomicReference<AiAuditRecord> capturedRecord = new AtomicReference<>();
        AiAuditLogHandler handler = capturedRecord::set;

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(handler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder()
                .model("deepseek-r1")
                .messages(List.of(UserMessage.from("执行分析")))
                .build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listener.onRequest(reqContext);

        ChatModelErrorContext errContext = new ChatModelErrorContext(
                new RuntimeException("模型服务暂时不可用 (503 Service Unavailable)"),
                request, null, reqContext.attributes());

        listener.onError(errContext);

        AiAuditRecord record = capturedRecord.get();
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo("ERROR");
        assertThat(record.getErrorMessage()).contains("503 Service Unavailable");
    }

    @Test
    @DisplayName("客户端主动中断推流场景：解耦标记 status 为 CANCELLED 防拉低模型可用率度量")
    void testClientAbortAudit() {
        MDC.put("traceId", "trace-cancel-01");
        AtomicReference<AiAuditRecord> capturedRecord = new AtomicReference<>();
        AiAuditLogHandler handler = capturedRecord::set;

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(handler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder()
                .model("deepseek-r1")
                .messages(List.of(UserMessage.from("流式分析长文本")))
                .build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listener.onRequest(reqContext);

        ChatModelErrorContext errContext = new ChatModelErrorContext(
                new IOException("Broken pipe"),
                request, null, reqContext.attributes());

        listener.onError(errContext);

        AiAuditRecord record = capturedRecord.get();
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo("CANCELLED");
        assertThat(record.getErrorMessage()).contains("Broken pipe");
    }

    @Test
    @DisplayName("AiAuditLogHandler 抛出异常时不阻断业务主链路")
    void testHandlerExceptionResilience() {
        AiAuditLogHandler failingHandler = record -> {
            throw new RuntimeException("Kafka 集群暂时离线");
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(failingHandler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder().model("test-model").build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listener.onRequest(reqContext);

        ChatModelResponse response = ChatModelResponse.builder().model("test-model").aiMessage(AiMessage.from("ok")).build();
        ChatModelResponseContext respContext = new ChatModelResponseContext(response, request, reqContext.attributes());

        assertThatCode(() -> listener.onResponse(respContext)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("配置 audit.enabled=false 时静默跳过审计生命周期")
    void testAuditDisabled() {
        properties.getAudit().setEnabled(false);

        AtomicReference<AiAuditRecord> capturedRecord = new AtomicReference<>();
        AiAuditLogHandler handler = capturedRecord::set;

        @SuppressWarnings("unchecked")
        ObjectProvider<AiAuditLogHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(handler);

        AtengAiObservabilityListener listener = new AtengAiObservabilityListener(properties, handlerProvider, null);

        ChatModelRequest request = ChatModelRequest.builder().model("test-model").build();
        ChatModelRequestContext reqContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listener.onRequest(reqContext);

        ChatModelResponse response = ChatModelResponse.builder().model("test-model").build();
        ChatModelResponseContext respContext = new ChatModelResponseContext(response, request, reqContext.attributes());
        listener.onResponse(respContext);

        assertThat(capturedRecord.get()).isNull();
    }
}
