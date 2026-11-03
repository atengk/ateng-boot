package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
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
 * 通过 event: reasoning 与 event: text 双通道独立推流，支持思考链解耦、全链路上下文透传与句柄安全排空
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class SseTokenStreamBridge implements StreamingResponseHandler<AiMessage> {

    private static final Logger log = LoggerFactory.getLogger(SseTokenStreamBridge.class);

    private final SseEmitter emitter;
    private final ReasonerTokenExtractor extractor;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AsyncContext.ContextSnapshot contextSnapshot;

    public SseTokenStreamBridge(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "SseEmitter 不能为空");
        this.extractor = new ReasonerTokenExtractor();
        this.contextSnapshot = AsyncContext.ContextSnapshot.capture();

        // 注册连接回调，防止客户端主动断连时出现句柄泄漏
        this.emitter.onCompletion(() -> completed.set(true));
        this.emitter.onTimeout(() -> {
            log.warn("SSE 连接响应超时，主动关闭推流管道");
            closeSafely();
        });
        this.emitter.onError(err -> {
            log.debug("SSE 管道发生异常中止: {}", err.getMessage());
            completed.set(true);
        });
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

            // 3. 闭合 HTTP SSE 连接管道
            closeSafely();
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
     * 发送 SSE 事件，针对网络套接字中断安全捕获
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
        } catch (Throwable t) {
            log.warn("发送 SSE 分片异常: {}", t.getMessage());
            closeSafely();
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

    public ReasonerTokenExtractor getExtractor() {
        return extractor;
    }
}
