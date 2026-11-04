package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.core.context.AsyncContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring MVC SseEmitter 与 LangChain4j 流式响应桥接器
 * 通过 event: reasoning 与 event: text 双通道独立推流，支持思考链解耦、全链路上下文透传与物理连接安全取消治理
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SseTokenStreamBridge implements StreamingResponseHandler<AiMessage> {

    private static final Logger log = LoggerFactory.getLogger(SseTokenStreamBridge.class);

    private final SseEmitter emitter;
    private final String streamId;
    private final ActiveStreamRegistry streamRegistry;
    private final ReasonerTokenExtractor extractor;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean normalCompleted = new AtomicBoolean(false);
    private final AsyncContext.ContextSnapshot contextSnapshot;

    public SseTokenStreamBridge(SseEmitter emitter) {
        this(emitter, null, null);
    }

    public SseTokenStreamBridge(SseEmitter emitter, String streamId, ActiveStreamRegistry streamRegistry) {
        this.emitter = Objects.requireNonNull(emitter, "SseEmitter 不能为空");
        this.streamId = streamId;
        this.streamRegistry = streamRegistry;
        this.extractor = new ReasonerTokenExtractor();
        this.contextSnapshot = AsyncContext.ContextSnapshot.capture();

        // 注册连接回调，防止客户端主动断连时出现句柄泄漏并立即物理切断底层 OkHttp 物理连接
        this.emitter.onCompletion(() -> {
            completed.set(true);
            if (normalCompleted.get()) {
                cleanupStreamSafely();
            } else {
                cancelStreamPhysically();
            }
        });
        this.emitter.onTimeout(() -> {
            log.warn("SSE 连接响应超时，主动关闭推流管道并切断物理连接 [streamId={}]", this.streamId);
            closeSafely();
            cancelStreamPhysically();
        });
        this.emitter.onError(err -> {
            log.debug("SSE 管道发生异常中止: {}", err.getMessage());
            completed.set(true);
            cancelStreamPhysically();
        });
    }

    public String getStreamId() {
        return streamId;
    }

    public ActiveStreamRegistry getStreamRegistry() {
        return streamRegistry;
    }

    /**
     * 将 LangChain4j 的声明式智能体 TokenStream 流式管道自动绑定并桥接到 Spring SseEmitter
     *
     * @param tokenStream 智能体 TokenStream 流
     * @param emitter     目标 SseEmitter 实例
     * @return 绑定的 SseTokenStreamBridge 实例
     */
    public static SseTokenStreamBridge pipe(TokenStream tokenStream, SseEmitter emitter) {
        return pipe(tokenStream, emitter, null, null);
    }

    /**
     * 将 LangChain4j 的声明式智能体 TokenStream 流式管道自动绑定并桥接到 Spring SseEmitter (支持物理中断注册)
     *
     * @param tokenStream    智能体 TokenStream 流
     * @param emitter        目标 SseEmitter 实例
     * @param streamId       可选的流式标识
     * @param streamRegistry 可选的活跃连接注册中心
     * @return 绑定的 SseTokenStreamBridge 实例
     */
    public static SseTokenStreamBridge pipe(TokenStream tokenStream, SseEmitter emitter, String streamId, ActiveStreamRegistry streamRegistry) {
        Objects.requireNonNull(tokenStream, "TokenStream 不能为空");
        SseTokenStreamBridge bridge = new SseTokenStreamBridge(emitter, streamId, streamRegistry);
        tokenStream
                .onNext(bridge::onNext)
                .onComplete(bridge::onComplete)
                .onError(bridge::onError)
                .start();
        return bridge;
    }

    @Override
    public void onNext(String token) {
        if (completed.get() || token == null) {
            return;
        }

        runWithContext(() -> {
            List<StreamToken> tokens = extractor.feed(token);
            for (StreamToken streamToken : tokens) {
                sendEvent(streamToken.channel().getEventName(), streamToken.text());
            }
        });
    }

    @Override
    public void onComplete(Response<AiMessage> response) {
        if (completed.get()) {
            return;
        }

        runWithContext(() -> {
            // 1. 排空思考链状态机中残留的最后一个 Token
            List<StreamToken> remaining = extractor.finish();
            for (StreamToken streamToken : remaining) {
                sendEvent(streamToken.channel().getEventName(), streamToken.text());
            }

            // 2. 推送标准结束事件
            sendEvent("done", "[DONE]");

            // 3. 闭合 HTTP SSE 连接管道并清理注册表
            normalCompleted.set(true);
            closeSafely();
            cleanupStreamSafely();
        });
    }

    @Override
    public void onError(Throwable error) {
        if (completed.get()) {
            return;
        }

        runWithContext(() -> {
            AiException aiEx = AiExceptionTranslator.translate(error);
            log.error("大模型流式输出发生未决异常: {}", aiEx.getMessage());

            sendEvent("error", aiEx.getMessage());

            try {
                emitter.completeWithError(aiEx);
            } catch (Throwable ignored) {
            } finally {
                completed.set(true);
                cancelStreamPhysically();
            }
        });
    }

    private void runWithContext(Runnable task) {
        AsyncContext.ContextSnapshot previous = AsyncContext.ContextSnapshot.capturePrevious();
        try {
            contextSnapshot.apply();
            task.run();
        } finally {
            previous.restore();
        }
    }

    /**
     * 发送 SSE 事件，针对网络套接字中断安全捕获并迅速触发底层物理切断
     *
     * @param eventName 事件名称 (reasoning / text / done / error)
     * @param data      事件数据
     */
    private synchronized void sendEvent(String eventName, Object data) {
        if (completed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException ex) {
            log.debug("客户端已断开 SSE 连接: {}", ex.getMessage());
            closeSafely();
            cancelStreamPhysically();
        } catch (Throwable t) {
            log.warn("发送 SSE 分片异常: {}", t.getMessage());
            closeSafely();
            cancelStreamPhysically();
        }
    }

    private void closeSafely() {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Throwable ignored) {
            }
        }
    }

    private void cancelStreamPhysically() {
        if (streamRegistry != null && streamId != null) {
            streamRegistry.cancel(streamId);
        }
    }

    private void cleanupStreamSafely() {
        if (streamRegistry != null && streamId != null) {
            streamRegistry.unregister(streamId);
        }
    }
}
