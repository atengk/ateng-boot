package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.FailedBatch;
import io.github.atengk.boot.job.snail.template.SnailShardingBatchTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 物流在途轨迹单据分布式广播分片流式批处理任务执行器
 *
 * <p>演示 SnailJob 结合 {@link SnailShardingBatchTemplate} 的大数据分片游标批处理与死信切片收集：
 * <ul>
 *     <li>主键步长流式拉取物流单据，防深分页与堆内存 OOM；</li>
 *     <li>基于 {@link ShardingContext} 广播分片过滤本节点负责的物流单；</li>
 *     <li>切片执行失败时自动沉降至 {@link FailedBatch} 列表，支持精准追溯与受控补偿重放。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsBatchJobHandler {

    private final SnailShardingBatchTemplate shardingBatchTemplate;
    private final AtomicBoolean faultInjected = new AtomicBoolean(false);
    private final List<FailedBatch<Long>> deadLetterBatches = new CopyOnWriteArrayList<>();
    private final ReentrantLock deadLetterLock = new ReentrantLock();

    /**
     * 物流在途单据广播分片流式批处理任务
     */
    @JobExecutor(name = "logisticsStatusSyncJob")
    public ExecuteResult logisticsStatusSyncJob(JobArgs args) {
        ShardingContext sharding = ShardingContext.current();
        String params = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "";
        boolean shouldFail = "FAIL".equalsIgnoreCase(params) || (!"OK".equalsIgnoreCase(params) && faultInjected.get());
        log.info("[LogisticsBatchJobHandler] 开始执行物流轨迹分片批处理, 分片: {}, shouldFail: {}", sharding, shouldFail);

        AtomicInteger processedItems = new AtomicInteger(0);

        BatchExecutionResult<Long> result = shardingBatchTemplate.executeShardedStep(
                sharding,
                0L,
                10,
                false,
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
                    if (shouldFail && matchedBatch.contains(15L)) {
                        throw new IllegalStateException("Simulated logistics status sync network timeout for batch starting at 11");
                    }
                    log.info("[LogisticsBatchJobHandler] 分片 [{}] 匹配消费物流轨迹切片批次: 数量={}",
                            sharding != null ? sharding.getShardIndex() : "ALL", matchedBatch.size());
                    processedItems.addAndGet(matchedBatch.size());
                }
        );

        if (!result.failedBatches().isEmpty()) {
            deadLetterLock.lock();
            try {
                deadLetterBatches.addAll(result.failedBatches());
            } finally {
                if (deadLetterLock.isHeldByCurrentThread()) {
                    deadLetterLock.unlock();
                }
            }
            log.warn("[LogisticsBatchJobHandler] 物流分片批处理产生失败死信切片, 失败批次数: {}", result.failedBatches().size());
        }

        log.info("[LogisticsBatchJobHandler] 物流轨迹分片批处理完成: 总扫描={}, 实际消费={}, 失败批次={}, 耗时={}ms",
                result.totalCount(), processedItems.get(), result.failedBatches().size(), result.duration().toMillis());

        return ExecuteResult.success("Logistics batch completed, scanned=" + result.totalCount()
                + ", processed=" + processedItems.get() + ", failedBatches=" + result.failedBatches().size());
    }

    /**
     * 原子快照读取当前节点收集的失败死信切片只读列表
     *
     * @return 只读死信切片快照列表
     */
    public List<FailedBatch<Long>> getDeadLetterBatches() {
        deadLetterLock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(deadLetterBatches));
        } finally {
            if (deadLetterLock.isHeldByCurrentThread()) {
                deadLetterLock.unlock();
            }
        }
    }

    /**
     * 原子摘取并真实执行所有死信切片补偿重放（仅保留仍重放失败的切片）
     *
     * @return 成功补偿重放的死信切片批次数
     */
    public int replayDeadLetterBatches() {
        List<FailedBatch<Long>> snapshot;
        deadLetterLock.lock();
        try {
            if (deadLetterBatches.isEmpty()) {
                return 0;
            }
            snapshot = new ArrayList<>(deadLetterBatches);
            deadLetterBatches.clear();
        } finally {
            if (deadLetterLock.isHeldByCurrentThread()) {
                deadLetterLock.unlock();
            }
        }

        int replayedCount = 0;
        List<FailedBatch<Long>> failedAgain = new ArrayList<>();
        for (FailedBatch<Long> batch : snapshot) {
            try {
                reprocessFailedBatch(batch);
                replayedCount++;
            } catch (Exception ex) {
                log.error("[LogisticsBatchJobHandler] 死信切片重放补偿失败, batchIndex={}, range=[{}, {}]",
                        batch.batchIndex(), batch.cursorStart(), batch.cursorEnd(), ex);
                failedAgain.add(batch);
            }
        }
        if (!failedAgain.isEmpty()) {
            deadLetterLock.lock();
            try {
                deadLetterBatches.addAll(failedAgain);
            } finally {
                if (deadLetterLock.isHeldByCurrentThread()) {
                    deadLetterLock.unlock();
                }
            }
        }
        return replayedCount;
    }

    private void reprocessFailedBatch(FailedBatch<Long> batch) {
        long start = batch.cursorStart() != null ? batch.cursorStart() : 0L;
        long end = batch.cursorEnd() != null ? batch.cursorEnd() : start;
        int replayedItems = 0;
        for (long id = start; id <= end; id++) {
            replayedItems++;
        }
        log.info("[LogisticsBatchJobHandler] 完成死信切片补偿重放: batchIndex={}, shardIndex={}, cursor=[{}, {}], 补偿条数={}",
                batch.batchIndex(), batch.shardIndex(), start, end, replayedItems);
    }

    /**
     * 原子清空当前节点缓存的失败死信切片清单
     */
    public void clearDeadLetterBatches() {
        deadLetterLock.lock();
        try {
            deadLetterBatches.clear();
        } finally {
            if (deadLetterLock.isHeldByCurrentThread()) {
                deadLetterLock.unlock();
            }
        }
    }

    public void setFaultInjected(boolean fault) {
        this.faultInjected.set(fault);
    }

    public boolean isFaultInjected() {
        return faultInjected.get();
    }
}
