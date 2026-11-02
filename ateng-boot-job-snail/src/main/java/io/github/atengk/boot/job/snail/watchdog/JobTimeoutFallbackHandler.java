package io.github.atengk.boot.job.snail.watchdog;

import java.time.Duration;

/**
 * SnailJob 本地硬看门狗超时降级补偿 SPI
 *
 * <p>当调度任务触发看门狗硬超时被强中断，且无方法级本地就地降级补偿或工作线程无响应时，
 * 全局触发该 SPI 处理器执行外部资源清理、分布式锁释放、业务状态回滚或异步告警上报。</p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@FunctionalInterface
public interface JobTimeoutFallbackHandler {

    /**
     * 超时降级补偿回调
     *
     * @param metadata 任务执行上下文元数据
     * @param elapsed  超时总耗时
     */
    void onTimeout(SnailJobExecutionMetadata metadata, Duration elapsed);
}
