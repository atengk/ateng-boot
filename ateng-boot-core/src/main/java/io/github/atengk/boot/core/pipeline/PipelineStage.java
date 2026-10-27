package io.github.atengk.boot.core.pipeline;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 强类型管道处理阶段函数式接口
 * 每一个阶段负责对上下文执行特定的业务逻辑处理或前置防卫校验
 *
 * @param <C> 管道上下文类型
 * @author Ateng
 * @since 2026-10-27
 */
@FunctionalInterface
public interface PipelineStage<C> {

    /**
     * 执行当前阶段业务逻辑
     *
     * @param context 管道上下文对象
     * @return 若返回 true 则继续流转到下一阶段；若返回 false 则立即短路终止执行
     * @throws Exception 业务阶段中抛出的异常
     */
    boolean process(C context) throws Exception;

    /**
     * 获取当前阶段的可读名称（默认使用实现类的 SimpleName）
     *
     * @return 阶段名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * 构造一个指定名称的通用阶段
     *
     * @param name  阶段可读名称
     * @param stage 阶段逻辑闭包
     * @param <C>   上下文类型
     * @return 命名阶段实例
     */
    static <C> PipelineStage<C> of(String name, PipelineStage<C> stage) {
        Objects.requireNonNull(name, "阶段名称 name 不能为 null");
        Objects.requireNonNull(stage, "阶段逻辑 stage 不能为 null");
        return new PipelineStage<>() {
            @Override
            public boolean process(C context) throws Exception {
                return stage.process(context);
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    /**
     * 构造一个基于谓词断言的命名阶段（断言成立继续，不成立短路）
     *
     * @param name      阶段可读名称
     * @param predicate 谓词断言
     * @param <C>       上下文类型
     * @return 命名断言阶段实例
     */
    static <C> PipelineStage<C> ofPredicate(String name, Predicate<C> predicate) {
        Objects.requireNonNull(name, "阶段名称 name 不能为 null");
        Objects.requireNonNull(predicate, "谓词断言 predicate 不能为 null");
        return new PipelineStage<>() {
            @Override
            public boolean process(C context) {
                return predicate.test(context);
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    /**
     * 构造一个基于消费动作的命名阶段（执行消费动作，默认恒定返回 true 继续流转）
     *
     * @param name     阶段可读名称
     * @param consumer 消费动作
     * @param <C>      上下文类型
     * @return 命名消费阶段实例
     */
    static <C> PipelineStage<C> ofConsumer(String name, Consumer<C> consumer) {
        Objects.requireNonNull(name, "阶段名称 name 不能为 null");
        Objects.requireNonNull(consumer, "消费动作 consumer 不能为 null");
        return new PipelineStage<>() {
            @Override
            public boolean process(C context) {
                consumer.accept(context);
                return true;
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }
}
