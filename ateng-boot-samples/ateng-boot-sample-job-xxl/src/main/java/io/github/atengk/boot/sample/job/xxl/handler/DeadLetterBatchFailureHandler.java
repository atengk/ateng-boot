package io.github.atengk.boot.sample.job.xxl.handler;

import io.github.atengk.boot.job.xxl.template.BatchFailureHandler;
import io.github.atengk.boot.job.xxl.template.FailedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局分片流式批处理死信切片兜底处理器 SPI
 *
 * <p>实现 {@link BatchFailureHandler} 函数式 SPI 并注册为 Spring 全局 Bean，
 * 当业务批处理未显式传入就近回调时，由框架自动仲裁路由至此全局兜底处理器统一收纳失败切片，
 * 供运维端 {@code XxlJobDeadLetterController} 统一回查与受控重放。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Component
public class DeadLetterBatchFailureHandler implements BatchFailureHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterBatchFailureHandler.class);

    private final List<FailedBatch<?>> globalDeadLetters = new CopyOnWriteArrayList<>();

    @Override
    public void onFailure(FailedBatch<Object> failedBatch) {
        if (failedBatch == null) {
            return;
        }
        log.warn(">>>>>> [DeadLetterBatchFailureHandler] 全局 SPI 兜底接收失败切片: batchIndex={}, cursorId={}, items={}, cause={}",
                failedBatch.getBatchIndex(),
                failedBatch.getCursorId(),
                failedBatch.getItems().size(),
                failedBatch.getCause() != null ? failedBatch.getCause().getMessage() : "none");
        globalDeadLetters.add(failedBatch);
    }

    /**
     * 获取全局 SPI 收集到的失败死信切片列表
     *
     * @return 失败死信列表
     */
    public List<FailedBatch<?>> getGlobalDeadLetters() {
        return Collections.unmodifiableList(globalDeadLetters);
    }

    /**
     * 清空全局 SPI 死信暂存记录
     */
    public void clearGlobalDeadLetters() {
        globalDeadLetters.clear();
    }
}
