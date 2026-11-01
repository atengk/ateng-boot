package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import io.github.atengk.boot.job.xxl.template.BatchExecutionResult;
import io.github.atengk.boot.job.xxl.template.FailedBatch;
import io.github.atengk.boot.job.xxl.template.XxlShardingBatchTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分片广播与流式防 OOM 批处理任务样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class ShardingBatchJobHandler {

    private static final Logger log = LoggerFactory.getLogger(ShardingBatchJobHandler.class);

    private final XxlShardingBatchTemplate batchTemplate;
    private final List<FailedBatch<MockOrder>> deadLetters = new CopyOnWriteArrayList<>();

    public ShardingBatchJobHandler(XxlShardingBatchTemplate batchTemplate) {
        this.batchTemplate = batchTemplate;
    }

    public List<FailedBatch<MockOrder>> getDeadLetters() {
        return deadLetters;
    }

    public void clearDeadLetters() {
        deadLetters.clear();
    }

    /**
     * 大数据流式分片批处理任务：自动注入 ShardingContext，结合 XxlShardingBatchTemplate 游标流式拉取与并发消费
     *
     * @param shardingContext 分片上下文 (分片索引与分片总数)
     */
    @XxlJob("shardingBatchJob")
    @AtengXxlJob(value = "shardingBatchJob", timeout = "30s")
    public void executeShardingBatch(ShardingContext shardingContext) {
        log.info(">>>>>> [ShardingBatchJobHandler] 开始执行分片任务: 当前节点分片={}/{}",
                shardingContext.getShardIndex(), shardingContext.getShardTotal());

        AtomicInteger processedCount = new AtomicInteger();

        // 模拟大数据流式游标拉取与分片智能过滤消费 (每批 100 条，总计模拟 3 批)
        BatchExecutionResult summary = batchTemplate.executeShardedStep(
                shardingContext,
                0L,
                100,
                (lastId, limit) -> {
                    long currentId = lastId != null ? lastId : 0L;
                    if (currentId >= 300) {
                        return List.of();
                    }
                    List<MockOrder> batch = new ArrayList<>();
                    for (int i = 1; i <= limit; i++) {
                        long id = currentId + i;
                        batch.add(new MockOrder(id, "ORDER-" + id, (int) (id % 100)));
                    }
                    return batch;
                },
                MockOrder::id,
                batch -> {
                    for (MockOrder order : batch) {
                        processedCount.incrementAndGet();
                    }
                }
        );

        XxlJobHelper.log("分片批处理执行完成: 成功处理 {} 条, 失败 {} 条, 总耗时 {}ms",
                summary.getSuccessCount(), summary.getFailCount(), summary.getDuration().toMillis());
        XxlJobHelper.handleSuccess(String.format("分片 %d 处理完毕, 累计消费 %d 条数据",
                shardingContext.getShardIndex(), summary.getSuccessCount()));
    }

    /**
     * 分片流式批处理与死信补偿回调任务：演示 structured 进度打卡与切片异常死信收集
     *
     * @param shardingContext 分片上下文
     */
    @XxlJob("shardingBatchDeadLetterJob")
    @AtengXxlJob(value = "shardingBatchDeadLetterJob", timeout = "30s")
    public void executeShardingBatchWithDeadLetter(ShardingContext shardingContext) {
        log.info(">>>>>> [ShardingBatchJobHandler] 开始执行带死信补偿回调的分片批处理任务...");
        deadLetters.clear();
        boolean originalFailFast = batchTemplate.isFailFast();
        batchTemplate.setFailFast(false);
        try {
            BatchExecutionResult summary = batchTemplate.executeShardedStep(
                    shardingContext,
                    0L,
                    5, // 每批 5 条，共 3 批 (15 条)
                    (lastId, limit) -> {
                        long currentId = lastId != null ? lastId : 0L;
                        if (currentId >= 15) {
                            return List.of();
                        }
                        List<MockOrder> batch = new ArrayList<>();
                        for (int i = 1; i <= limit; i++) {
                            long id = currentId + i;
                            batch.add(new MockOrder(id, "ORDER-" + id, (int) (id % 100)));
                        }
                        return batch;
                    },
                    MockOrder::id,
                    batch -> {
                        for (MockOrder order : batch) {
                            // 模拟 ID 为 8 的订单处理时抛出业务异常 (处于第 2 批: 6..10)
                            if (order.id() == 8L) {
                                throw new RuntimeException("订单 [ID=" + order.id() + "] 模拟业务处理失败");
                            }
                        }
                    },
                    failedBatch -> {
                        log.warn(">>>>>> [DeadLetter] 捕获失败切片进入死信回调: 批次 {}, 失败条数 {}",
                                failedBatch.getBatchIndex(), failedBatch.getItems().size());
                        deadLetters.add(failedBatch);
                    }
            );

            XxlJobHelper.handleSuccess(String.format("分片批处理完成: 成功 %d 条, 失败 %d 条, 死信收集 %d 批",
                    summary.getSuccessCount(), summary.getFailCount(), deadLetters.size()));
        } finally {
            batchTemplate.setFailFast(originalFailFast);
        }
    }

    public record MockOrder(Long id, String orderNo, Integer amount) {}
}
