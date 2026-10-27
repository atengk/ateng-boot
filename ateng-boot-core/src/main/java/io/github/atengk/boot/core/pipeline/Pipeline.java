package io.github.atengk.boot.core.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 纯 JDK 17 原生强类型业务管道与责任链编排器
 * 支持 Fluent 链式装配、阶段式短路阻断、上下文中断感知与快速失败结果契约，零外部依赖
 *
 * @param <C> 管道上下文类型
 * @author Ateng
 * @since 2026-10-27
 */
public final class Pipeline<C> {

    private final List<PipelineStage<C>> stages;
    private final PipelineErrorHandler<C> defaultErrorHandler;

    private Pipeline(List<PipelineStage<C>> stages, PipelineErrorHandler<C> defaultErrorHandler) {
        this.stages = List.copyOf(stages);
        this.defaultErrorHandler = defaultErrorHandler;
    }

    /**
     * 创建一个强类型管道构建器
     *
     * @param <C> 上下文类型
     * @return 管道构建器实例
     */
    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    /**
     * 基于阶段列表快速构造静态管道
     *
     * @param stages 阶段可变参数
     * @param <C>    上下文类型
     * @return 管道实例
     */
    @SafeVarargs
    public static <C> Pipeline<C> of(PipelineStage<C>... stages) {
        Objects.requireNonNull(stages, "阶段数组 stages 不能为 null");
        Builder<C> builder = builder();
        for (PipelineStage<C> stage : stages) {
            builder.addStage(stage);
        }
        return builder.build();
    }

    /**
     * 基于阶段集合快速构造静态管道
     *
     * @param stages 阶段集合
     * @param <C>    上下文类型
     * @return 管道实例
     */
    public static <C> Pipeline<C> of(List<PipelineStage<C>> stages) {
        Objects.requireNonNull(stages, "阶段列表 stages 不能为 null");
        Builder<C> builder = builder();
        builder.addStages(stages);
        return builder.build();
    }

    /**
     * 执行管道
     *
     * @param context 管道上下文对象
     * @return 管道执行结果
     */
    public PipelineResult execute(C context) {
        return execute(context, (Consumer<PipelineResult>) null);
    }

    /**
     * 执行管道并支持结果回调消费
     *
     * @param context  管道上下文对象
     * @param callback 结果回调函数
     * @return 管道执行结果
     */
    public PipelineResult execute(C context, Consumer<PipelineResult> callback) {
        return executeInternal(context, this.defaultErrorHandler, callback);
    }

    /**
     * 执行管道并支持临时指定自定义错误处理器
     *
     * @param context      管道上下文对象
     * @param errorHandler 错误处理器
     * @return 管道执行结果
     */
    public PipelineResult execute(C context, PipelineErrorHandler<C> errorHandler) {
        return executeInternal(context, errorHandler != null ? errorHandler : this.defaultErrorHandler, null);
    }

    private PipelineResult executeInternal(C context, PipelineErrorHandler<C> errorHandler, Consumer<PipelineResult> callback) {
        Objects.requireNonNull(context, "管道上下文 context 不能为 null");

        PipelineResult result = runStages(context, errorHandler);

        if (callback != null) {
            callback.accept(result);
        }
        return result;
    }

    private PipelineResult runStages(C context, PipelineErrorHandler<C> errorHandler) {
        for (PipelineStage<C> stage : stages) {
            // 1. 前置检查上下文是否已被显式触发中断
            if (isContextInterrupted(context)) {
                return PipelineResult.terminated(stage.getName(), getContextInterruptReason(context));
            }

            // 2. 执行当前阶段业务逻辑
            boolean proceed;
            try {
                proceed = stage.process(context);
            } catch (Throwable cause) {
                if (errorHandler != null) {
                    try {
                        errorHandler.handleError(stage, context, cause);
                    } catch (Throwable handlerEx) {
                        cause.addSuppressed(handlerEx);
                    }
                }
                return PipelineResult.failure(stage.getName(), cause);
            }

            // 3. 检查阶段返回值是否指示短路或上下文触发了中断
            if (!proceed || isContextInterrupted(context)) {
                return PipelineResult.terminated(stage.getName(), getContextInterruptReason(context));
            }
        }

        return PipelineResult.success();
    }

    private boolean isContextInterrupted(C context) {
        return context instanceof PipelineInterruptible interruptible && interruptible.isInterrupted();
    }

    private String getContextInterruptReason(C context) {
        if (context instanceof PipelineInterruptible interruptible && interruptible.isInterrupted()) {
            return interruptible.getInterruptReason();
        }
        return null;
    }

    /**
     * 获取当前管道的所有不可变阶段列表
     *
     * @return 阶段列表
     */
    public List<PipelineStage<C>> getStages() {
        return stages;
    }

    /**
     * 强类型管道构建器
     *
     * @param <C> 上下文类型
     */
    public static final class Builder<C> {
        private final List<PipelineStage<C>> stages = new ArrayList<>();
        private PipelineErrorHandler<C> errorHandler;

        private Builder() {
        }

        /**
         * 向管道尾部追加一个处理阶段
         *
         * @param stage 阶段逻辑
         * @return 当前构建器
         */
        public Builder<C> addStage(PipelineStage<C> stage) {
            Objects.requireNonNull(stage, "阶段逻辑 stage 不能为 null");
            this.stages.add(stage);
            return this;
        }

        /**
         * 向管道尾部追加一个具名处理阶段
         *
         * @param name  阶段可读名称
         * @param stage 阶段逻辑
         * @return 当前构建器
         */
        public Builder<C> addStage(String name, PipelineStage<C> stage) {
            return addStage(PipelineStage.of(name, stage));
        }

        /**
         * 批量追加阶段列表
         *
         * @param stages 阶段集合
         * @return 当前构建器
         */
        public Builder<C> addStages(List<PipelineStage<C>> stages) {
            if (stages != null) {
                for (PipelineStage<C> stage : stages) {
                    addStage(stage);
                }
            }
            return this;
        }

        /**
         * 设置全局默认异常处理器
         *
         * @param errorHandler 异常处理器
         * @return 当前构建器
         */
        public Builder<C> setErrorHandler(PipelineErrorHandler<C> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /**
         * 构建不可变管道实例
         *
         * @return 管道实例
         */
        public Pipeline<C> build() {
            return new Pipeline<>(this.stages, this.errorHandler);
        }
    }
}
