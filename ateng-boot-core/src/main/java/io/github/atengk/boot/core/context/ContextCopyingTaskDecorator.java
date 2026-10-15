package io.github.atengk.boot.core.context;

import org.springframework.core.task.TaskDecorator;

/**
 * 上下文感知任务装饰器
 * 基于 Spring {@link TaskDecorator} 契约，自动委托 {@link AsyncContext#wrap(Runnable)} 传播 MDC 链路追踪标识与用户身份上下文
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class ContextCopyingTaskDecorator implements TaskDecorator {

    /**
     * 包装待执行的异步 Runnable 任务，实现调用方父线程上下文的抓取、注入与执行后安全还原
     *
     * @param runnable 待包装的原始任务
     * @return 注入父线程上下文快照的增强任务，入参为 null 时返回 null
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        return AsyncContext.wrap(runnable);
    }
}
