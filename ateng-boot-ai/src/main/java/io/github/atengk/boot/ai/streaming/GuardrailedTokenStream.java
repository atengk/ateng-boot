package io.github.atengk.boot.ai.streaming;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.guardrail.AiGuardrail;
import io.github.atengk.boot.ai.guardrail.StreamingGuardrailSession;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 声明式智能体流式输出实时内容安全护栏包装器
 * <p>
 * 在流式 {@code onNext} 阶段按累计文本增量执行 {@link AiGuardrail#inspectResponse(String)} 校验；
 * 一旦命中违规立即通过 {@link ActiveStreamRegistry#cancel(String)} 物理切断上游连接并终止违规推流。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class GuardrailedTokenStream implements TokenStream {

    private final TokenStream delegate;
    private final AiGuardrail guardrail;
    private final String streamId;
    private final ActiveStreamRegistry streamRegistry;
    private final StreamingGuardrailSession guardrailSession;

    private Consumer<String> nextHandler;
    private Consumer<Response<AiMessage>> completeHandler;
    private Consumer<Throwable> errorHandler;

    public GuardrailedTokenStream(TokenStream delegate,
                                  AiGuardrail guardrail,
                                  String streamId,
                                  ActiveStreamRegistry streamRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "TokenStream 不能为空");
        this.guardrail = Objects.requireNonNull(guardrail, "AiGuardrail 不能为空");
        this.streamId = streamId;
        this.streamRegistry = streamRegistry;
        this.guardrailSession = new StreamingGuardrailSession(guardrail);
    }

    /**
     * 获取关联的内容安全护栏实例
     *
     * @return AiGuardrail 实例
     */
    public AiGuardrail getGuardrail() {
        return guardrail;
    }

    /**
     * 获取当前流关联的流式唯一标识
     *
     * @return 流式唯一标识
     */
    public String getStreamId() {
        return streamId;
    }

    /**
     * 获取关联的活跃流式连接注册中心
     *
     * @return ActiveStreamRegistry 实例
     */
    public ActiveStreamRegistry getStreamRegistry() {
        return streamRegistry;
    }

    /**
     * 解包获取原始底层 TokenStream
     *
     * @return 原始 TokenStream 实例
     */
    public TokenStream unwrap() {
        return delegate;
    }

    @Override
    public TokenStream onNext(Consumer<String> tokenHandler) {
        this.nextHandler = tokenHandler;
        return this;
    }

    @Override
    public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
        delegate.onRetrieved(contentHandler);
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
        delegate.onToolExecuted(toolExecuteHandler);
        return this;
    }

    @Override
    public TokenStream onComplete(Consumer<Response<AiMessage>> completionHandler) {
        this.completeHandler = completionHandler;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        delegate.ignoreErrors();
        return this;
    }

    @Override
    public void start() {
        delegate.onNext(token -> {
            if (guardrailSession.isTerminated() || token == null) {
                return;
            }
            StreamingGuardrailSession.Decision decision = guardrailSession.evaluateNext(token, this::cancelPhysically);
            if (decision.status() == StreamingGuardrailSession.DecisionStatus.BLOCK) {
                if (errorHandler != null) {
                    errorHandler.accept(new AiException(AiResultCode.AI_CONTENT_RISK_DETECTED, decision.reason()));
                }
                return;
            }
            if (decision.status() == StreamingGuardrailSession.DecisionStatus.REPLACE) {
                String safe = decision.safeContent();
                if (nextHandler != null) {
                    nextHandler.accept(safe);
                }
                if (completeHandler != null) {
                    completeHandler.accept(Response.from(AiMessage.from(safe)));
                }
                return;
            }
            if (decision.status() == StreamingGuardrailSession.DecisionStatus.TERMINATED) {
                return;
            }
            if (nextHandler != null) {
                nextHandler.accept(token);
            }
        });

        delegate.onComplete(response -> {
            if (guardrailSession.isTerminated()) {
                return;
            }
            if (completeHandler != null) {
                completeHandler.accept(response);
            }
        });

        delegate.onError(error -> {
            if (guardrailSession.isTerminated()) {
                return;
            }
            if (errorHandler != null) {
                errorHandler.accept(error);
            }
        });

        String prevStreamId = StreamContextHolder.getStreamId();
        boolean boundLocally = false;
        if (StringUtils.hasText(streamId) && !StringUtils.hasText(prevStreamId)) {
            StreamContextHolder.setStreamId(streamId);
            boundLocally = true;
        }
        try {
            delegate.start();
        } finally {
            if (boundLocally) {
                StreamContextHolder.clear();
            }
        }
    }

    private void cancelPhysically() {
        if (streamRegistry != null && StringUtils.hasText(streamId)) {
            streamRegistry.cancel(streamId);
        }
    }
}
