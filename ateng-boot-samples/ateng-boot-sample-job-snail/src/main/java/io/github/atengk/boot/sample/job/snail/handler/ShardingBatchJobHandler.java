package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式广播分片流式批处理任务执行器示例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShardingBatchJobHandler {

    private final SnailShardingBatchTemplate shardingBatchTemplate;

    /**
     * 模拟订单分片批处理调度任务
     */
    @JobExecutor(name = "shardingBatchJob")
    public ExecuteResult shardingBatchJob(JobArgs args) {
        ShardingContext sharding = ShardingContext.current();
        log.info("[ShardingBatchJobHandler] 开始执行分布式分片批处理, 当前分片: {}", sharding);

        // 模拟生成内存测试订单 ID 数据源 (1 ~ 30)
        AtomicInteger processedItems = new AtomicInteger(0);

        BatchExecutionResult result = shardingBatchTemplate.executeShardedStep(
                sharding,
                0L,
                10,
                (lastId, limit) -> {
                    List<Long> mockBatch = new ArrayList<>();
                    long start = lastId == null ? 1L : lastId + 1L;
                    for (long id = start; id < start + limit && id <= 30; id++) {
                        mockBatch.add(id);
                    }
                    return mockBatch;
                },
                id -> id,
                matchedBatch -> {
                    log.info("[ShardingBatchJobHandler] 分片 [{}] 匹配消费切片批次: 数量={}",
                            sharding != null ? sharding.getShardIndex() : "ALL", matchedBatch.size());
                    processedItems.addAndGet(matchedBatch.size());
                }
        );

        log.info("[ShardingBatchJobHandler] 分布式分片批处理完成: 总扫描={}, 实际消费={}, 耗时={}ms",
                result.totalCount(), processedItems.get(), result.duration().toMillis());

        return ExecuteResult.success("Sharding batch completed, scanned=" + result.totalCount() + ", processed=" + processedItems.get());
    }
}
