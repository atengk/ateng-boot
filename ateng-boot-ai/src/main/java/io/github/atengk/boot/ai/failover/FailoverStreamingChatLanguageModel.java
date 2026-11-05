package io.github.atengk.boot.ai.failover;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 声明式多模型故障自动转移异步流式聊天模型代理
 * <p>
 * 当主流式模型在输出首个 Token 前发生连接中断、超时或上游 502/503/504 故障时，
 * 在全局时延预算 (Total Deadline Budget) 允许范围内，自动透明故障转移至备用流式模型。
 * 遵循流式提交不变量：一旦首个 Token 已经向下游成功发射，流式已不可撤销提交，后续异常直接通知下游，防御重复投递。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class FailoverStreamingChatLanguageModel implements StreamingChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverStreamingChatLanguageModel.class);

    private final List<NamedModel<StreamingChatLanguageModel>> candidateModels;
    private final Duration totalTimeout;

    public FailoverStreamingChatLanguageModel(List<NamedModel<StreamingChatLanguageModel>> candidateModels) {
        this(candidateModels, null);
    }

    public FailoverStreamingChatLanguageModel(List<NamedModel<StreamingChatLanguageModel>> candidateModels, Duration totalTimeout) {
        Assert.notEmpty(candidateModels, "候选流式大模型序列 candidateModels 不能为空");
        this.candidateModels = Collections.unmodifiableList(new ArrayList<>(candidateModels));
        this.totalTimeout = totalTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        Assert.notNull(handler, "StreamingResponseHandler 不能为空");
        attemptStream(0, messages, null, null, handler, TotalDeadline.of(totalTimeout));
    }

    @Override
    public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, StreamingResponseHandler<AiMessage> handler) {
        Assert.notNull(handler, "StreamingResponseHandler 不能为空");
        attemptStream(0, messages, toolSpecifications, null, handler, TotalDeadline.of(totalTimeout));
    }

    @Override
    public void generate(List<ChatMessage> messages, ToolSpecification toolSpecification, StreamingResponseHandler<AiMessage> handler) {
        Assert.notNull(handler, "StreamingResponseHandler 不能为空");
        attemptStream(0, messages, null, toolSpecification, handler, TotalDeadline.of(totalTimeout));
    }

    private void attemptStream(
            int candidateIndex,
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            ToolSpecification toolSpecification,
            StreamingResponseHandler<AiMessage> downstreamHandler,
            TotalDeadline deadline) {

        if (candidateIndex >= candidateModels.size()) {
            downstreamHandler.onError(new AiException(AiResultCode.AI_CALL_FAILED, "全部候选流式大模型调用耗尽"));
            return;
        }

        NamedModel<StreamingChatLanguageModel> candidate = candidateModels.get(candidateIndex);
        String modelName = candidate.name();
        StreamingChatLanguageModel model = candidate.model();
        boolean isLast = (candidateIndex == candidateModels.size() - 1);

        // 1. 检查时延预算
        if (deadline.isExpired()) {
            log.warn("[AI-FAILOVER-STREAM] 全局时延预算已耗尽，终止流式故障转移 [model={}]", modelName);
            downstreamHandler.onError(new AiException(AiResultCode.AI_TIMEOUT,
                    "流式调用已耗尽全局时延预算 (总时限: " + totalTimeout + ")"));
            return;
        }

        // 2. 状态防卫拦截器：记录首个 Token 是否已发射及终态是否已到达
        AtomicBoolean committed = new AtomicBoolean(false);
        AtomicBoolean completedOrFailed = new AtomicBoolean(false);

        StreamingResponseHandler<AiMessage> interceptor = new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                committed.set(true);
                downstreamHandler.onNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                if (completedOrFailed.compareAndSet(false, true)) {
                    downstreamHandler.onComplete(response);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!completedOrFailed.compareAndSet(false, true)) {
                    return;
                }

                // 铁律：若已经向客户端输出了 Token，流式已不可撤销提交，坚决不能次生重发/转移
                if (committed.get()) {
                    log.warn("[AI-FAILOVER-STREAM] 模型 [{}] 在输出 Token 后发生异常，流已提交，直接通知下游: {}",
                            modelName, error.getMessage());
                    downstreamHandler.onError(error);
                    return;
                }

                boolean failoverable = FailoverClassifier.isFailoverable(error);
                if (!failoverable) {
                    log.warn("[AI-FAILOVER-STREAM] 模型 [{}] 抛出非瞬态异常 ({})，属于快速失败范畴，终止故障转移",
                            modelName, error.getMessage());
                    downstreamHandler.onError(AiExceptionTranslator.translate(error));
                    return;
                }

                if (isLast) {
                    log.error("[AI-FAILOVER-STREAM] 候选流式大模型序列已全数耗尽，最终模型 [{}] 失败: {}", modelName, error.getMessage());
                    downstreamHandler.onError(AiExceptionTranslator.translate("全部候选流式大模型调用耗尽", error));
                    return;
                }

                if (deadline.isExpired()) {
                    log.warn("[AI-FAILOVER-STREAM] 模型 [{}] 失败且全局时延预算已耗尽，终止转移", modelName);
                    downstreamHandler.onError(new AiException(AiResultCode.AI_TIMEOUT,
                            String.format("模型 [%s] 失败且已耗尽全局时延预算: %s", modelName, error.getMessage()), error));
                    return;
                }

                String nextModelName = candidateModels.get(candidateIndex + 1).name();
                log.warn("[AI-FAILOVER-STREAM] 模型 [{}] 未输出 Token 即失败 ({})，正在故障转移至下一个流式模型 [{}] (剩余预算: {}ms)...",
                        modelName, error.getMessage(), nextModelName, deadline.getRemainingMillis());

                attemptStream(candidateIndex + 1, messages, toolSpecifications, toolSpecification, downstreamHandler, deadline);
            }
        };

        try {
            if (toolSpecifications != null) {
                model.generate(messages, toolSpecifications, interceptor);
            } else if (toolSpecification != null) {
                model.generate(messages, toolSpecification, interceptor);
            } else {
                model.generate(messages, interceptor);
            }
        } catch (Throwable t) {
            // 同步调用阶段抛出异常，统一流转至 interceptor.onError 处理
            interceptor.onError(t);
        }
    }

    public List<NamedModel<StreamingChatLanguageModel>> getCandidateModels() {
        return candidateModels;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public NamedModel<StreamingChatLanguageModel> getPrimaryModel() {
        return candidateModels.get(0);
    }

    public List<NamedModel<StreamingChatLanguageModel>> getFallbackModels() {
        return candidateModels.subList(1, candidateModels.size());
    }

    /**
     * 流式链式构造器
     */
    public static class Builder {
        private final List<NamedModel<StreamingChatLanguageModel>> candidates = new ArrayList<>();
        private Duration totalTimeout;

        public Builder addModel(String name, StreamingChatLanguageModel model) {
            candidates.add(NamedModel.of(name, model));
            return this;
        }

        public Builder addModel(NamedModel<StreamingChatLanguageModel> namedModel) {
            if (namedModel != null) {
                candidates.add(namedModel);
            }
            return this;
        }

        public Builder primary(String name, StreamingChatLanguageModel model) {
            if (!candidates.isEmpty()) {
                candidates.add(0, NamedModel.of(name, model));
            } else {
                candidates.add(NamedModel.of(name, model));
            }
            return this;
        }

        public Builder fallback(String name, StreamingChatLanguageModel model) {
            candidates.add(NamedModel.of(name, model));
            return this;
        }

        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
            return this;
        }

        public FailoverStreamingChatLanguageModel build() {
            return new FailoverStreamingChatLanguageModel(candidates, totalTimeout);
        }
    }
}
