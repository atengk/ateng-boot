package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import io.github.atengk.boot.job.xxl.template.BatchExecutionResult;
import io.github.atengk.boot.job.xxl.template.XxlShardingBatchTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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

    public ShardingBatchJobHandler(XxlShardingBatchTemplate batchTemplate) {
        this.batchTemplate = batchTemplate;
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

    public record MockOrder(Long id, String orderNo, Integer amount) {}
}
