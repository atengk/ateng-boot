package io.github.atengk.boot.ai.graph.pipeline;

import io.github.atengk.boot.ai.graph.BaseGraphState;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 确定性顺序流水线阶段契约模型
 * <p>
 * 封装流水线单个阶段的元数据与运行时行为，包括阶段唯一标识、执行处理器、条件跳过断言、
 * 静态挂起中断（HITL）以及异常容错策略。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
public class PipelineStage<S extends BaseGraphState> {

    private final String name;
    private final String description;
    private final StageHandler<S> handler;
    private final Predicate<S> skipCondition;
    private final boolean interruptBefore;
    private final boolean continueOnError;

    /**
     * 全参构造器
     *
     * @param name            阶段唯一标识（不可为空且不可与保留字冲突）
     * @param description     阶段描述（可为空）
     * @param handler         阶段处理器（不可为空）
     * @param skipCondition   条件跳过断言（为空表示不跳过）
     * @param interruptBefore 是否在前置进入阶段前触发静态挂起中断
     * @param continueOnError 阶段发生异常时是否容错继续向下执行
     */
    public PipelineStage(String name,
                         String description,
                         StageHandler<S> handler,
                         Predicate<S> skipCondition,
                         boolean interruptBefore,
                         boolean continueOnError) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PipelineStage name must not be null or blank");
        }
        this.name = name.trim();
        this.description = description != null ? description.trim() : "";
        this.handler = Objects.requireNonNull(handler, "PipelineStage handler must not be null");
        this.skipCondition = skipCondition;
        this.interruptBefore = interruptBefore;
        this.continueOnError = continueOnError;
    }

    /**
     * 极简便捷静态构造器
     *
     * @param name    阶段唯一标识
     * @param handler 阶段处理器
     * @param <S>     状态模型泛型
     * @return 流水线阶段实例
     */
    public static <S extends BaseGraphState> PipelineStage<S> of(String name, StageHandler<S> handler) {
        return new PipelineStage<>(name, "", handler, null, false, false);
    }

    /**
     * 带条件跳过断言的便捷静态构造器
     *
     * @param name          阶段唯一标识
     * @param handler       阶段处理器
     * @param skipCondition 条件跳过断言
     * @param <S>           状态模型泛型
     * @return 流水线阶段实例
     */
    public static <S extends BaseGraphState> PipelineStage<S> of(String name, StageHandler<S> handler, Predicate<S> skipCondition) {
        return new PipelineStage<>(name, "", handler, skipCondition, false, false);
    }

    /**
     * 带描述的便捷静态构造器
     *
     * @param name        阶段唯一标识
     * @param description 阶段描述
     * @param handler     阶段处理器
     * @param <S>         状态模型泛型
     * @return 流水线阶段实例
     */
    public static <S extends BaseGraphState> PipelineStage<S> of(String name, String description, StageHandler<S> handler) {
        return new PipelineStage<>(name, description, handler, null, false, false);
    }

    /**
     * 带描述与条件跳过断言的便捷静态构造器
     *
     * @param name          阶段唯一标识
     * @param description   阶段描述
     * @param handler       阶段处理器
     * @param skipCondition 条件跳过断言
     * @param <S>           状态模型泛型
     * @return 流水线阶段实例
     */
    public static <S extends BaseGraphState> PipelineStage<S> of(String name, String description, StageHandler<S> handler, Predicate<S> skipCondition) {
        return new PipelineStage<>(name, description, handler, skipCondition, false, false);
    }

    /**
     * 创建流水线阶段构建器
     *
     * @param <S> 状态模型泛型
     * @return 构建器实例
     */
    public static <S extends BaseGraphState> Builder<S> builder() {
        return new Builder<>();
    }

    /**
     * 评估当前阶段是否应当跳过执行
     *
     * @param state 当前状态快照
     * @return true 表示跳过当前阶段，false 表示正常执行
     */
    public boolean shouldSkip(S state) {
        return skipCondition != null && skipCondition.test(state);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public StageHandler<S> getHandler() {
        return handler;
    }

    public Predicate<S> getSkipCondition() {
        return skipCondition;
    }

    public boolean isInterruptBefore() {
        return interruptBefore;
    }

    public boolean isContinueOnError() {
        return continueOnError;
    }

    @Override
    public String toString() {
        return "PipelineStage{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", interruptBefore=" + interruptBefore +
                ", continueOnError=" + continueOnError +
                '}';
    }

    /**
     * 流水线阶段建造者
     *
     * @param <S> 状态模型泛型
     */
    public static class Builder<S extends BaseGraphState> {
        private String name;
        private String description;
        private StageHandler<S> handler;
        private Predicate<S> skipCondition;
        private boolean interruptBefore;
        private boolean continueOnError;

        public Builder<S> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<S> description(String description) {
            this.description = description;
            return this;
        }

        public Builder<S> handler(StageHandler<S> handler) {
            this.handler = handler;
            return this;
        }

        public Builder<S> skipCondition(Predicate<S> skipCondition) {
            this.skipCondition = skipCondition;
            return this;
        }

        public Builder<S> interruptBefore(boolean interruptBefore) {
            this.interruptBefore = interruptBefore;
            return this;
        }

        public Builder<S> continueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public PipelineStage<S> build() {
            return new PipelineStage<>(name, description, handler, skipCondition, interruptBefore, continueOnError);
        }
    }
}
