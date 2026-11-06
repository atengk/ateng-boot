package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.rag.citation.Citation;
import io.github.atengk.boot.ai.rag.citation.CitationExtractor;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
    private final StringBuilder textAccumulator = new StringBuilder();
    private List<TextSegment> citationCandidates;
    private Function<String, List<Citation>> citationExtractor;

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
     * 配置可信 RAG 证据溯源候选切片，流式推流结束时将自动提取引用并增量推送 event: citation 专属数据包
     *
     * @param candidates 知识库候选切片列表
     * @return 当前 SseTokenStreamBridge 实例
     */
    public SseTokenStreamBridge withCitationCandidates(List<TextSegment> candidates) {
        this.citationCandidates = candidates != null ? new ArrayList<>(candidates) : Collections.emptyList();
        return this;
    }

    /**
     * 配置自定义引用提取器，流式推流结束时将自动提取引用并增量推送 event: citation 专属数据包
     *
     * @param extractor 自定义引用提取逻辑
     * @return 当前 SseTokenStreamBridge 实例
     */
    public SseTokenStreamBridge withCitationExtractor(Function<String, List<Citation>> extractor) {
        this.citationExtractor = extractor;
        return this;
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
                if (streamToken.channel() == TokenChannel.TEXT) {
                    textAccumulator.append(streamToken.text());
                }
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
                if (streamToken.channel() == TokenChannel.TEXT) {
                    textAccumulator.append(streamToken.text());
                }
                sendEvent(streamToken.channel().getEventName(), streamToken.text());
            }

            // 1.5 如果配置了引用提取，在正文打字机推流完成后增量下发专属 event: citation 数据包
            sendCitationEventIfConfigured(response);

            // 2. 推送标准结束事件
            sendEvent("done", "[DONE]");

            // 3. 闭合 HTTP SSE 连接管道并清理注册表
            normalCompleted.set(true);
            closeSafely();
            cleanupStreamSafely();
        });
    }

    private void sendCitationEventIfConfigured(Response<AiMessage> response) {
        if (citationExtractor != null || citationCandidates != null) {
            try {
                String fullText = (response != null && response.content() != null && StringUtils.hasText(response.content().text()))
                        ? response.content().text()
                        : textAccumulator.toString();
                List<Citation> citations;
                if (citationExtractor != null) {
                    citations = citationExtractor.apply(fullText);
                } else {
                    citations = CitationExtractor.extractCitations(fullText, citationCandidates);
                }
                sendEvent("citation", JsonUtil.toJsonString(citations != null ? citations : Collections.emptyList()));
            } catch (Throwable t) {
                log.warn("提取并推送 SSE citation 增量数据包异常: {}", t.getMessage());
            }
        }
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
