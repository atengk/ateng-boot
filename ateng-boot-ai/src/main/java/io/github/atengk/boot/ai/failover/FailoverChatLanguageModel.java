package io.github.atengk.boot.ai.failover;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
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

/**
 * 声明式多模型故障自动转移同步聊天模型代理
 * <p>
 * 当主模型发生网络中断、读写超时或上游 502/503/504 故障时，在全局时延预算 (Total Deadline Budget)
 * 允许的范围内，自动按序无缝故障转移至候选备用模型；若遇到 4xx 客户端错误或提示词非法，则立即 Fail-Fast 阻断重试。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class FailoverChatLanguageModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatLanguageModel.class);

    private final List<NamedModel<ChatLanguageModel>> candidateModels;
    private final Duration totalTimeout;

    public FailoverChatLanguageModel(List<NamedModel<ChatLanguageModel>> candidateModels) {
        this(candidateModels, null);
    }

    public FailoverChatLanguageModel(List<NamedModel<ChatLanguageModel>> candidateModels, Duration totalTimeout) {
        Assert.notEmpty(candidateModels, "候选大模型序列 candidateModels 不能为空");
        this.candidateModels = Collections.unmodifiableList(new ArrayList<>(candidateModels));
        this.totalTimeout = totalTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return executeWithFailover(model -> model.generate(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return executeWithFailover(model -> model.generate(messages, toolSpecifications));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return executeWithFailover(model -> model.generate(messages, toolSpecification));
    }

    @FunctionalInterface
    private interface ModelExecution {
        Response<AiMessage> execute(ChatLanguageModel model);
    }

    private Response<AiMessage> executeWithFailover(ModelExecution execution) {
        TotalDeadline deadline = TotalDeadline.of(this.totalTimeout);
        Throwable lastException = null;

        for (int i = 0; i < candidateModels.size(); i++) {
            NamedModel<ChatLanguageModel> candidate = candidateModels.get(i);
            String modelName = candidate.name();
            ChatLanguageModel model = candidate.model();
            boolean isLast = (i == candidateModels.size() - 1);

            // 1. 检查全局时延预算
            try {
                deadline.checkNotExpired("调用大模型 [" + modelName + "]");
            } catch (AiException timeoutEx) {
                if (lastException != null) {
                    throw AiExceptionTranslator.translate("故障转移超时中断", lastException);
                }
                throw timeoutEx;
            }

            try {
                log.debug("[AI-FAILOVER] 尝试执行大模型调用 [model={}, index={}/{}]",
                        modelName, i + 1, candidateModels.size());
                return execution.execute(model);
            } catch (Throwable t) {
                lastException = t;
                boolean failoverable = FailoverClassifier.isFailoverable(t);

                if (!failoverable) {
                    // 非瞬态错误快速失败，直接向外抛出转译异常，遵循无 Log-and-Throw 规范
                    throw AiExceptionTranslator.translate(t);
                }

                if (isLast) {
                    // 候选序列耗尽直接向外抛出异常
                    throw AiExceptionTranslator.translate("全部候选大模型调用耗尽", t);
                }

                // 2. 检查是否有剩余预算尝试下一个备选模型
                if (deadline.isExpired()) {
                    throw new AiException(AiResultCode.AI_TIMEOUT,
                            String.format("大模型 [%s] 失败且已耗尽全局时延预算 (总时限 %s): %s",
                                    modelName, totalTimeout, t.getMessage()), t);
                }

                String nextModelName = candidateModels.get(i + 1).name();
                log.warn("[AI-FAILOVER] 大模型 [{}] 遇到可故障转移异常 ({})，正在转移至下一个候选模型 [{}] (剩余预算: {}ms)...",
                        modelName, t.getMessage(), nextModelName, deadline.getRemainingMillis());
            }
        }

        throw new AiException(AiResultCode.AI_CALL_FAILED, "候选大模型列表为空或未配置有效模型");
    }

    public List<NamedModel<ChatLanguageModel>> getCandidateModels() {
        return candidateModels;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public NamedModel<ChatLanguageModel> getPrimaryModel() {
        return candidateModels.get(0);
    }

    public List<NamedModel<ChatLanguageModel>> getFallbackModels() {
        return candidateModels.subList(1, candidateModels.size());
    }

    /**
     * 流式链式构造器
     */
    public static class Builder {
        private final List<NamedModel<ChatLanguageModel>> candidates = new ArrayList<>();
        private Duration totalTimeout;

        public Builder addModel(String name, ChatLanguageModel model) {
            candidates.add(NamedModel.of(name, model));
            return this;
        }

        public Builder addModel(NamedModel<ChatLanguageModel> namedModel) {
            if (namedModel != null) {
                candidates.add(namedModel);
            }
            return this;
        }

        public Builder primary(String name, ChatLanguageModel model) {
            if (!candidates.isEmpty()) {
                candidates.add(0, NamedModel.of(name, model));
            } else {
                candidates.add(NamedModel.of(name, model));
            }
            return this;
        }

        public Builder fallback(String name, ChatLanguageModel model) {
            candidates.add(NamedModel.of(name, model));
            return this;
        }

        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
            return this;
        }

        public FailoverChatLanguageModel build() {
            return new FailoverChatLanguageModel(candidates, totalTimeout);
        }
    }
}
