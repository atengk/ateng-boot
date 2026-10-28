package io.github.atengk.boot.storage.context;

import org.springframework.core.task.TaskDecorator;

/**
 * 存储上下文感知异步任务装饰器
 * 基于 Spring {@link TaskDecorator} 契约，自动委托 {@link StorageContextHolder#wrap(Runnable)}
 * 传播存储活动源及 Core 模块四维上下文（MDC 链路、用户、租户快照）
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class StorageTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return StorageContextHolder.wrap(runnable);
    }
}
